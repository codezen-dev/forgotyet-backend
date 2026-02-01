package com.fly.forgotyet.service;

import com.fly.forgotyet.entity.*;

import com.fly.forgotyet.enums.Complexity;
import com.fly.forgotyet.enums.TriggerBucket;
import com.fly.forgotyet.enums.TriggerIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@Slf4j
public class TriggerPlanService {

    private final Clock clock;
    private final UserBiasService userBiasService;

    public TriggerPlanService(Clock clock, UserBiasService userBiasService) {
        this.clock = clock;
        this.userBiasService = userBiasService;
    }


    /**
     * @param userId  先留着，未来接 UserPreference 用
     * @param r       LLM parse result（包含 intent/complexity/等）
     * @param eventTime 事实时间（已转 Instant）
     */
    public TriggerPlan plan(String userId, EventParseResult r, Instant eventTime) {
        Instant now = Instant.now(clock);

        TriggerPlan plan = new TriggerPlan();
        plan.setEventTime(eventTime);
        plan.setIntent(defaultIntent(r.getIntent()));
        plan.setComplexity(defaultComplexity(r.getComplexity()));

        Duration horizon = Duration.between(now, eventTime);
        if (r.isUrgentMinuteLevel()) {
            TriggerBucket bucket = chooseAtTimeBucket(horizon); // ✅ 核心修复点
            Instant trigger = safeTriggerTime(now, eventTime, bucket, plan);
            plan.setBucket(bucket);
            plan.setTriggerTime(trigger);
            plan.setForcedImmediate(true); // 字段名不改，语义是“分钟级强规则”
            plan.setReason("forced urgentMinuteLevel => bucket=" + bucket + ", horizon=" + horizon.toMinutes() + "m");
            return plan;
        }

        TriggerBucket bucket = chooseBaseBucket(plan.getIntent(), plan.getComplexity(), horizon);


        if (r.isPrepRequired()) {
            bucket = earlier(bucket);
        }
        if (hasDeps(r.getDependencies())) {
            bucket = earlier(bucket);
        }

        // 5) 偏好进化
        int biasSteps = userBiasService.computeBiasSteps(userId);
        bucket = shift(bucket, biasSteps);


        // 6) 计算 triggerTime，并约束/fallback
        Instant trigger = safeTriggerTime(now, eventTime, bucket, plan);

        plan.setBucket(bucket);
        plan.setTriggerTime(trigger);
        plan.setReason("intent=" + plan.getIntent()
                + ", complexity=" + plan.getComplexity()
                + ", prepRequired=" + r.isPrepRequired()
                + ", deps=" + (r.getDependencies() == null ? 0 : r.getDependencies().size())
                + ", biasSteps=" + biasSteps
                + ", finalBucket=" + bucket);
        log.info("[bias] user=" + userId + " biasSteps=" + biasSteps + " bucket=" + bucket);
        return plan;
    }

    private TriggerIntent defaultIntent(TriggerIntent intent) {
        return intent == null ? TriggerIntent.CUSHION : intent;
    }

    private Complexity defaultComplexity(Complexity c) {
        return c == null ? Complexity.MEDIUM : c;
    }

    private boolean hasDeps(List<String> deps) {
        return deps != null && !deps.isEmpty();
    }

    private static final TriggerBucket[] ORDER = new TriggerBucket[] {
            TriggerBucket.M0,
            TriggerBucket.M1,
            TriggerBucket.M5,
            TriggerBucket.M10,
            TriggerBucket.M15,
            TriggerBucket.M30,
            TriggerBucket.H1,
            TriggerBucket.H2,
            TriggerBucket.H4,
            TriggerBucket.H8,
            TriggerBucket.D1,
            TriggerBucket.D3,
            TriggerBucket.D7,
            TriggerBucket.D14,
            TriggerBucket.D30
    };

    private TriggerBucket earlier(TriggerBucket b) {
        int idx = indexOf(b);
        return ORDER[Math.min(ORDER.length - 1, idx + 1)];
    }

    private TriggerBucket later(TriggerBucket b) {
        int idx = indexOf(b);
        return ORDER[Math.max(0, idx - 1)];
    }

    private int indexOf(TriggerBucket b) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] == b) return i;
        }
        return 0;
    }


    private TriggerBucket shift(TriggerBucket b, int biasSteps) {
        TriggerBucket cur = b;

        // 正值：更晚（减少提前量）
        if (biasSteps > 0) {
            for (int i = 0; i < biasSteps; i++) cur = later(cur);
        }
        // 负值：更早（增加提前量）
        else if (biasSteps < 0) {
            for (int i = 0; i < -biasSteps; i++) cur = earlier(cur);
        }
        return cur;
    }


    /**
     * 核心约束：
     * - triggerTime 必须 < eventTime
     * - 若算出来 <= now，则 fallback = now + 1min
     */
    private Instant safeTriggerTime(Instant now, Instant eventTime, TriggerBucket bucket, TriggerPlan plan) {
        Duration lead = TriggerBucketUtil.toDuration(bucket);
        Instant trigger = eventTime.minus(lead);

        // 如果 eventTime 本身就在过去（LLM/兜底导致），那就立即触发（给 scheduler 一个最近未来时间）
        if (!eventTime.isAfter(now)) {
            plan.setFallbackNowPlus1m(true);
            plan.setReason("eventTime_in_past => schedule_now+5s");
            return now.plusSeconds(5);
        }

        // 正常情况：必须 < eventTime
        if (!trigger.isBefore(eventTime)) {
            trigger = eventTime.minusMillis(1);
        }

        // 如果 trigger 已经过了（<=now），给一个“尽快但仍早于eventTime”的时间
        if (!trigger.isAfter(now)) {
            plan.setFallbackNowPlus1m(true);

            // 尽快触发时间：now+5s
            Instant soon = now.plusSeconds(5);

            // 但必须早于 eventTime：最多 eventTime-1s
            Instant latestBeforeEvent = eventTime.minusSeconds(1);

            // 取两者较小：既尽快，又不越过 eventTime
            if (soon.isBefore(latestBeforeEvent)) {
                return soon;
            }
            // 如果 eventTime 距离太近（<6秒），那就直接 eventTime-1s
            return latestBeforeEvent;
        }

        return trigger;
    }
    private TriggerBucket chooseAtTimeBucket(Duration horizon) {
        // horizon: eventTime - now（肯定是正数才会进来）
        if (horizon.compareTo(Duration.ofMinutes(1)) <= 0) return TriggerBucket.M0;
        if (horizon.compareTo(Duration.ofMinutes(5)) <= 0) return TriggerBucket.M1;
        if (horizon.compareTo(Duration.ofMinutes(10)) <= 0) return TriggerBucket.M5;
        if (horizon.compareTo(Duration.ofMinutes(30)) <= 0) return TriggerBucket.M10;
        if (horizon.compareTo(Duration.ofHours(2)) <= 0) return TriggerBucket.M15;
        if (horizon.compareTo(Duration.ofHours(8)) <= 0) return TriggerBucket.M30;
        return TriggerBucket.H1;
    }


    private TriggerBucket chooseBaseBucket(TriggerIntent intent, Complexity c, Duration horizon) {
        // intent=AT_TIME：尽量贴近事件
        if (intent == TriggerIntent.AT_TIME) {
            if (horizon.compareTo(Duration.ofMinutes(2)) <= 0) return TriggerBucket.M0;
            if (horizon.compareTo(Duration.ofMinutes(10)) <= 0) return TriggerBucket.M1;
            if (horizon.compareTo(Duration.ofMinutes(30)) <= 0) return TriggerBucket.M5;
            if (horizon.compareTo(Duration.ofHours(2)) <= 0) return TriggerBucket.M15;
            if (horizon.compareTo(Duration.ofHours(8)) <= 0) return TriggerBucket.M30;
            return TriggerBucket.H1;
        }

        // 非 AT_TIME：先按 horizon 分段，再按复杂度微调
        // 你可以理解为：越远的事，越需要“至少提前几天/几周”来兜底准备
        TriggerBucket base;
        if (horizon.compareTo(Duration.ofMinutes(30)) <= 0) base = TriggerBucket.M5;
        else if (horizon.compareTo(Duration.ofHours(2)) <= 0) base = TriggerBucket.M15;
        else if (horizon.compareTo(Duration.ofHours(8)) <= 0) base = TriggerBucket.H1;
        else if (horizon.compareTo(Duration.ofDays(1)) <= 0) base = TriggerBucket.H4;
        else if (horizon.compareTo(Duration.ofDays(3)) <= 0) base = TriggerBucket.D1;
        else if (horizon.compareTo(Duration.ofDays(14)) <= 0) base = TriggerBucket.D3;
        else if (horizon.compareTo(Duration.ofDays(45)) <= 0) base = TriggerBucket.D7;
        else base = TriggerBucket.D14;

        // intent=PREPARE：整体更早一档
        if (intent == TriggerIntent.PREPARE) base = earlier(base);

        // complexity 越高，再更早
        if (c == Complexity.HIGH) base = earlier(base);
        else if (c == Complexity.LOW) base = later(base);

        return base;
    }

}

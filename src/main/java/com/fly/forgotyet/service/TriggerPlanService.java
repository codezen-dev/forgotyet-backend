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

        // 1) 强规则：分钟/马上/立刻/立即/过X分钟 -> bucket = M0
        if (r.isUrgentMinuteLevel()) {
            TriggerBucket bucket = TriggerBucket.M0;
            Instant trigger = safeTriggerTime(now, eventTime, bucket, plan);
            plan.setBucket(bucket);
            plan.setTriggerTime(trigger);
            plan.setForcedImmediate(true);
            plan.setReason("forced urgentMinuteLevel => bucket=M0");
            return plan;
        }

        // 2) 初始 bucket（按 intent + complexity）
        TriggerBucket bucket = chooseBaseBucket(plan.getIntent(), plan.getComplexity());

        // 3) 默认策略：偏早一档（保守兜底）
        bucket = earlier(bucket);

        // 4) 若需要准备/有依赖，再偏早
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

    private TriggerBucket chooseBaseBucket(TriggerIntent intent, Complexity c) {
        return switch (intent) {
            case AT_TIME -> TriggerBucket.M0;
            case CUSHION -> switch (c) {
                case LOW -> TriggerBucket.H1;
                case MEDIUM -> TriggerBucket.H4;
                case HIGH -> TriggerBucket.D1;
            };
            case PREPARE -> switch (c) {
                case LOW -> TriggerBucket.H4;
                case MEDIUM -> TriggerBucket.D1;
                case HIGH -> TriggerBucket.D3;
            };
        };
    }

    /**
     * 偏早一档（更早触发）：
     * M0 -> M15 -> H1 -> H4 -> D1 -> D3
     */
    private TriggerBucket earlier(TriggerBucket b) {
        return switch (b) {
            case M0 -> TriggerBucket.M15;
            case M15 -> TriggerBucket.H1;
            case H1 -> TriggerBucket.H4;
            case H4 -> TriggerBucket.D1;
            case D1 -> TriggerBucket.D3;
            case D3 -> TriggerBucket.D3;
        };
    }

    /**
     * 偏晚一档（更晚触发）：
     * D3 -> D1 -> H4 -> H1 -> M15 -> M0
     */
    private TriggerBucket later(TriggerBucket b) {
        return switch (b) {
            case M0 -> TriggerBucket.M0;
            case M15 -> TriggerBucket.M0;
            case H1 -> TriggerBucket.M15;
            case H4 -> TriggerBucket.H1;
            case D1 -> TriggerBucket.H4;
            case D3 -> TriggerBucket.D1;
        };
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

        // 必须 < eventTime：如果触发时间不在 eventTime 前面，则往前挪 1ms
        if (!trigger.isBefore(eventTime)) {
            trigger = eventTime.minusMillis(1);
        }

        // 如果算出来在过去/现在 -> fallback now + 1min
        if (!trigger.isAfter(now)) {
            plan.setFallbackNowPlus1m(true);
            return now.plus(Duration.ofMinutes(1));
        }
        return trigger;
    }
}

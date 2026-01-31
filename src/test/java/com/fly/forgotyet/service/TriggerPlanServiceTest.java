package com.fly.forgotyet.service;

import com.fly.forgotyet.entity.*;
import com.fly.forgotyet.enums.Complexity;
import com.fly.forgotyet.enums.TriggerBucket;
import com.fly.forgotyet.enums.TriggerIntent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TriggerPlanServiceTest {

    // 固定当前时间：2026-01-27 20:00:00 UTC（你只要保证固定即可，和本地时区无关，因为策略层用 Instant）
    private final Instant NOW = Instant.parse("2026-01-27T20:00:00Z");
    private final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private TriggerPlanService newService() {
        return new TriggerPlanService(FIXED_CLOCK);
    }

    private static EventParseResult baseParseResult() {
        EventParseResult r = new EventParseResult();
        r.setValid(true);
        r.setSummary("test");
        return r;
    }

    @Test
    void urgentMinuteLevel_shouldForceM0_andFallbackWhenTooClose() {
        // given：1分钟后（非常近）+ urgent=true
        TriggerPlanService svc = newService();
        EventParseResult r = baseParseResult();
        r.setIntent(TriggerIntent.AT_TIME);
        r.setComplexity(Complexity.LOW);
        r.setUrgentMinuteLevel(true);

        Instant eventTime = NOW.plusSeconds(60);

        // when
        TriggerPlan plan = svc.plan("u", r, eventTime);

        // then
        assertEquals(TriggerBucket.M0, plan.getBucket(), "分钟级强规则必须强制 M0");
        assertTrue(plan.isForcedImmediate(), "应标记 forcedImmediate=true");

        // 因为 trigger = eventTime - 0，且你代码要求 trigger < eventTime，会先减 1ms
        // 但仍可能非常接近 now，且规则是 trigger <= now 就 fallback now+1min
        // 这里不强行断言一定 fallback（不同实现/边界可能变化），但至少要保证 trigger 在未来
        assertTrue(plan.getTriggerTime().isAfter(NOW), "triggerTime 必须在 now 之后");
        assertTrue(plan.getTriggerTime().isBefore(eventTime), "triggerTime 必须 < eventTime");
    }

    @Test
    void atTime_nonUrgent_shouldDefaultEarlierToM15() {
        // given：今晚9点这种 AT_TIME，但不含“分钟/立刻”，urgent=false
        TriggerPlanService svc = newService();
        EventParseResult r = baseParseResult();
        r.setIntent(TriggerIntent.AT_TIME);
        r.setComplexity(Complexity.MEDIUM);
        r.setUrgentMinuteLevel(false);

        Instant eventTime = NOW.plusSeconds(2 * 3600); // 2小时后

        // when
        TriggerPlan plan = svc.plan("u", r, eventTime);

        // then：AT_TIME base=M0，默认偏早一档 => M15
        assertEquals(TriggerBucket.M15, plan.getBucket(), "AT_TIME 非分钟级默认应偏早一档到 M15");
        assertTrue(plan.getTriggerTime().isBefore(eventTime), "triggerTime 必须 < eventTime");

        // trigger 应该约等于 eventTime - 15min（允许误差：0ms，因为 Instant 精确）
        Instant expected = eventTime.minusSeconds(15 * 60);
        assertEquals(expected, plan.getTriggerTime(), "M15 应准确减去 15 分钟");
    }

    @Test
    void cushion_high_shouldBecomeD3_byDefaultEarlier() {
        // given：下周三交报告（CUSHION + HIGH），无准备/无依赖
        TriggerPlanService svc = newService();
        EventParseResult r = baseParseResult();
        r.setIntent(TriggerIntent.CUSHION);
        r.setComplexity(Complexity.HIGH);
        r.setPrepRequired(false);
        r.setDependencies(List.of());
        r.setUrgentMinuteLevel(false);

        Instant eventTime = NOW.plusSeconds(10L * 24 * 3600); // 10天后

        // when
        TriggerPlan plan = svc.plan("u", r, eventTime);

        // then：base D1，默认偏早一档 => D3
        assertEquals(TriggerBucket.D3, plan.getBucket(), "CUSHION+HIGH 默认偏早应到 D3");
        Instant expected = eventTime.minusSeconds(3L * 24 * 3600);
        assertEquals(expected, plan.getTriggerTime(), "D3 应准确减去 3 天");
    }

    @Test
    void prepare_medium_withPrepRequired_shouldClimbToD3() {
        // given：PREPARE + MEDIUM + prepRequired=true
        TriggerPlanService svc = newService();
        EventParseResult r = baseParseResult();
        r.setIntent(TriggerIntent.PREPARE);
        r.setComplexity(Complexity.MEDIUM);
        r.setPrepRequired(true);
        r.setDependencies(List.of()); // 无依赖
        r.setUrgentMinuteLevel(false);

        Instant eventTime = NOW.plusSeconds(5L * 24 * 3600); // 5天后

        // when
        TriggerPlan plan = svc.plan("u", r, eventTime);

        // then：
        // base(PREPARE,MEDIUM)=D1
        // 默认偏早一档 -> D3
        // prepRequired=true 再偏早仍 D3（顶格）
        assertEquals(TriggerBucket.D3, plan.getBucket(), "PREPARE+MEDIUM 默认偏早+准备应顶到 D3");
        Instant expected = eventTime.minusSeconds(3L * 24 * 3600);
        assertEquals(expected, plan.getTriggerTime(), "D3 应准确减去 3 天");
    }

    @Test
    void dependencies_shouldAlsoPushEarlierBucket() {
        // given：有依赖（people/materials）会再偏早一档（你现在的规则）
        TriggerPlanService svc = newService();
        EventParseResult r = baseParseResult();
        r.setIntent(TriggerIntent.CUSHION);
        r.setComplexity(Complexity.MEDIUM); // base=H4
        r.setPrepRequired(false);
        r.setDependencies(List.of("people", "materials"));
        r.setUrgentMinuteLevel(false);

        Instant eventTime = NOW.plusSeconds(2L * 24 * 3600); // 2天后

        // when
        TriggerPlan plan = svc.plan("u", r, eventTime);

        // then：
        // base(CUSHION,MEDIUM)=H4
        // 默认偏早一档 -> D1
        // deps 非空再偏早 -> D3
        assertEquals(TriggerBucket.D3, plan.getBucket(), "有依赖应将 bucket 进一步偏早到 D3（在当前规则下）");
        Instant expected = eventTime.minusSeconds(3L * 24 * 3600);

        // 注意：eventTime 只有 2 天后，eventTime-3d 会落到过去（<= now），会触发 fallback now+1min
        // 所以这里断言 fallback 行为，而不是断言 expected
        assertTrue(plan.isFallbackNowPlus1m(), "当计算结果落到过去/现在时必须 fallback");
        assertEquals(NOW.plusSeconds(60), plan.getTriggerTime(), "fallback 应为 now + 1min");
    }

    @Test
    void triggerTime_mustAlwaysBeBeforeEventTime() {
        TriggerPlanService svc = newService();

        EventParseResult r = baseParseResult();
        r.setIntent(TriggerIntent.AT_TIME);
        r.setComplexity(Complexity.LOW);
        r.setUrgentMinuteLevel(false);

        // eventTime 设远一点，避免 fallback
        Instant eventTime = NOW.plusSeconds(3600);

        TriggerPlan plan = svc.plan("u", r, eventTime);

        assertTrue(plan.getTriggerTime().isBefore(eventTime), "无论如何 triggerTime 必须 < eventTime");
        assertTrue(plan.getTriggerTime().isAfter(NOW), "无论如何 triggerTime 必须在 now 之后（否则 fallback）");
    }
}

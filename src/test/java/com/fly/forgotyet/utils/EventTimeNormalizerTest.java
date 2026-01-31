package com.fly.forgotyet.utils;

import com.fly.forgotyet.entity.EventParseResult;
import org.junit.jupiter.api.Test;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

public class EventTimeNormalizerTest {

    private EventParseResult pr(String raw, String eventTime) {
        EventParseResult r = new EventParseResult();
        r.setRawUserText(raw);
        r.setEventTime(eventTime);
        return r;
    }

    @Test
    void should_keep_llm_eventTime_if_present() {
        // Clock 的 zone 会影响 LocalDateTime.now(clock)，这里用上海时区对齐
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Clock clock = Clock.fixed(
                ZonedDateTime.of(2026, 1, 31, 10, 0, 0, 0, zone).toInstant(),
                zone
        );

        EventParseResult r = pr("明天下午去医院", "2026-02-01 15:30:00");
        EventTimeNormalizer.Result res = EventTimeNormalizer.normalize(r, r.getRawUserText(), clock);

        assertEquals(LocalDateTime.of(2026, 2, 1, 15, 30, 0), res.getEventTime());
        assertFalse(res.isAssumed());
        assertEquals("llm_eventTime_used", res.getReason());
    }

    @Test
    void should_roll_to_tomorrow_when_only_period_mentioned_and_past() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        // now = 2026-01-31 21:00
        Clock clock = Clock.fixed(
                ZonedDateTime.of(2026, 1, 31, 21, 0, 0, 0, zone).toInstant(),
                zone
        );

        EventParseResult r = pr("下午去医院", null);
        EventTimeNormalizer.Result res = EventTimeNormalizer.normalize(r, r.getRawUserText(), clock);

        assertTrue(res.isAssumed());
        assertTrue(res.getReason().contains("rolled_to_tomorrow"));
        assertEquals(LocalDateTime.of(2026, 2, 1, 15, 0, 0), res.getEventTime());
    }

    @Test
    void should_not_roll_if_explicit_today() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        // now = 2026-01-31 21:00
        Clock clock = Clock.fixed(
                ZonedDateTime.of(2026, 1, 31, 21, 0, 0, 0, zone).toInstant(),
                zone
        );

        EventParseResult r = pr("今天下午去医院", null);
        EventTimeNormalizer.Result res = EventTimeNormalizer.normalize(r, r.getRawUserText(), clock);

        assertTrue(res.isAssumed());
        assertFalse(res.getReason().contains("rolled_to_tomorrow"));
        assertEquals(LocalDateTime.of(2026, 1, 31, 15, 0, 0), res.getEventTime());
    }

    @Test
    void should_assume_soon_now_plus_10_minutes() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        // now = 2026-01-31 10:00
        Clock clock = Clock.fixed(
                ZonedDateTime.of(2026, 1, 31, 10, 0, 0, 0, zone).toInstant(),
                zone
        );

        EventParseResult r = pr("等一下我要去喂鱼", null);
        EventTimeNormalizer.Result res = EventTimeNormalizer.normalize(r, r.getRawUserText(), clock);

        assertTrue(res.isAssumed());
        assertTrue(res.getReason().contains("soon_now+10m"));
        assertEquals(LocalDateTime.of(2026, 1, 31, 10, 10, 0), res.getEventTime());
    }
}

package com.fly.forgotyet.utils;

import com.fly.forgotyet.entity.EventParseResult;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;

public class EventTimeNormalizer {

    private static final DateTimeFormatter ISO_LOCAL = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // 兜底锚点（你原来的版本）
    private static final LocalTime T_09 = LocalTime.of(9, 0);
    private static final LocalTime T_15 = LocalTime.of(15, 0);
    private static final LocalTime T_20 = LocalTime.of(20, 0);

    /**
     * C3-1: 模糊时间兜底
     * - LLM 有精确 eventTime：直接用（assumed=false）
     * - 没有 eventTime：用典型时间锚点兜底（assumed=true）
     * - C3-1.1：如果只说“下午/晚上”等时段词，且兜底落到过去，则顺延到明天同一时段（assumed=true，reason 带 rolled_to_tomorrow）
     */
    public static Result normalize(EventParseResult r, String rawInput, Clock clock) {
        ZonedDateTime nowZ = ZonedDateTime.now(clock);
        LocalDateTime now = nowZ.toLocalDateTime();

        // 1) LLM 给了 eventTime：直接用
        String llmEventTime = (r == null ? null : trimToNull(r.getEventTime()));
        if (llmEventTime != null) {
            try {
                LocalDateTime t = parseLocalDateTimeLenient(llmEventTime);

                return Result.of(t, false, "llm_eventTime_used");
            } catch (Exception ignore) {
                // 解析失败：仍然保守兜底，避免 NPE
                LocalDateTime fallback = now.plusMinutes(10);
                return Result.of(fallback, true, "fuzzy_assumed:llm_eventTime_parse_failed|soon_now+10m");
            }
        }

        String raw = trimToNull(rawInput);
        if (raw == null) {
            // 没原文：保守兜底
            return Result.of(now.plusMinutes(10), true, "fuzzy_assumed:missing_raw|soon_now+10m");
        }

        boolean hasToday = containsAny(raw, "今天", "今日");
        boolean hasTomorrow = containsAny(raw, "明天", "明日");
        boolean hasAfterTomorrow = containsAny(raw, "后天");
        boolean hasNextWeek = containsAny(raw, "下周");
        boolean hasNextMonth = containsAny(raw, "下个月");

        boolean hasAfternoon = containsAny(raw, "下午");
        boolean hasEvening = containsAny(raw, "晚上", "夜里", "夜晚");
        boolean hasSoon = containsAny(raw, "等一下", "等会", "等一会", "稍后", "一会", "一会儿");

        // 2) “等一下/稍后”且没有分钟 => now+10m
        if (hasSoon && !containsAny(raw, "分钟", "分", "min", "minute")) {
            return Result.of(now.plusMinutes(10), true, "fuzzy_assumed:soon_now+10m");
        }

        // 3) Date anchor
        LocalDate date;
        String dateReason;
        if (hasTomorrow) {
            date = now.toLocalDate().plusDays(1);
            dateReason = "tomorrow";
        } else if (hasAfterTomorrow) {
            date = now.toLocalDate().plusDays(2);
            dateReason = "after_tomorrow";
        } else if (hasNextWeek) {
            date = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            dateReason = "next_week_monday";
        } else if (hasNextMonth) {
            date = now.toLocalDate().withDayOfMonth(1).plusMonths(1);
            dateReason = "next_month_1st";
        } else if (hasToday) {
            date = now.toLocalDate();
            dateReason = "today";
        } else {
            date = now.toLocalDate();
            dateReason = "implicit_today";
        }

        // 4) Time anchor
        LocalTime time;
        String timeReason;
        if (hasEvening) {
            time = T_20;
            timeReason = "evening_20:00";
        } else if (hasAfternoon) {
            time = T_15;
            timeReason = "afternoon_15:00";
        } else {
            // 其它模糊：默认 09:00
            time = T_09;
            timeReason = "default_09:00";
        }

        LocalDateTime anchored = LocalDateTime.of(date, time);

        // 5) C3-1.1：只说“下午/晚上”等时段词，但没说今天/明天/下周/下个月，
        // 且 anchored 已经落到过去 => 顺延到明天同一时段
        boolean explicitDateMentioned = hasToday || hasTomorrow || hasAfterTomorrow || hasNextWeek || hasNextMonth;
        boolean hasMorning = containsAny(raw, "早上", "上午", "清晨");
        boolean hasNoon = containsAny(raw, "中午");
        boolean onlyPeriodMentioned = (hasMorning || hasNoon || hasAfternoon || hasEvening) && !explicitDateMentioned;


        String reason = "fuzzy_assumed:" + dateReason + "+" + timeReason;
        if (onlyPeriodMentioned && !anchored.isAfter(now)) {
            anchored = anchored.plusDays(1);
            reason += "|rolled_to_tomorrow";
        }

        return Result.of(anchored, true, reason);
    }

    // ===== Result =====
    public static class Result {
        private final LocalDateTime eventTime;
        private final boolean assumed;
        private final String reason;

        private Result(LocalDateTime eventTime, boolean assumed, String reason) {
            this.eventTime = eventTime;
            this.assumed = assumed;
            this.reason = reason;
        }

        public static Result of(LocalDateTime eventTime, boolean assumed, String reason) {
            return new Result(eventTime, assumed, reason);
        }

        public LocalDateTime getEventTime() {
            return eventTime;
        }

        public boolean isAssumed() {
            return assumed;
        }

        public String getReason() {
            return reason;
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean containsAny(String text, String... tokens) {
        for (String t : tokens) {
            if (t != null && !t.isEmpty() && text.contains(t)) return true;
        }
        return false;
    }

    private static LocalDateTime parseLocalDateTimeLenient(String s) {
        // 兼容 "2026-02-01T15:30:00" 和 "2026-02-01 15:30:00"
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception ignored) { }
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception ignored) { }
        // 允许少一位秒："yyyy-MM-dd HH:mm"
        return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

}

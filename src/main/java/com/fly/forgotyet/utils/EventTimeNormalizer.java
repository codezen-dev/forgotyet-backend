package com.fly.forgotyet.utils;

import com.fly.forgotyet.entity.EventParseResult;
import lombok.Data;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * C3-1: 模糊时间兜底（最小可用）
 * 只处理常见口语：今天/明天/后天/下午/晚上/中午/早上/下周/下个月
 *
 * 设计原则：
 * 1) 不覆盖 LLM 给出的精确时间
 * 2) 仅当 eventTime 缺失或明显不精确时才补全
 * 3) 一律把时间锚定到一个“典型时刻”，并写入 reason 便于解释
 */
public class EventTimeNormalizer {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    // 典型时间锚点（你觉得不舒服可以调整）
    private static final LocalTime MORNING = LocalTime.of(9, 0);
    private static final LocalTime NOON = LocalTime.of(12, 0);
    private static final LocalTime AFTERNOON = LocalTime.of(15, 0);
    private static final LocalTime EVENING = LocalTime.of(20, 0);

    @Data
    public static class Result {
        /** 归一化后的 eventTime（一定不为空） */
        private LocalDateTime eventTime;
        /** 是否发生了兜底补全 */
        private boolean assumed;
        /** 兜底原因（写入 triggerReason） */
        private String reason;
    }

    public static Result normalize(EventParseResult r, String rawInput, Clock clock) {
        Result out = new Result();
        LocalDateTime now = LocalDateTime.now(clock);

        // 1) 如果 LLM 给了 eventTime，先尝试解析
        LocalDateTime parsed = parseEventTime(r);

        // 2) 判断是否“足够精确”，足够精确就直接用
        // 你现在的 LLM 返回格式是 "yyyy-MM-dd HH:mm:ss" 或 null
        if (parsed != null) {
            out.setEventTime(parsed);
            out.setAssumed(false);
            out.setReason("llm_eventTime_used");
            return out;
        }

        // 3) LLM 没给时间：根据 rawInput 做最小兜底
        String text = (rawInput == null ? "" : rawInput).toLowerCase(Locale.ROOT);

        LocalDate baseDate = now.toLocalDate();
        String dateReason = "today";

        // 日期粒度
        if (containsAny(text, "明天")) {
            baseDate = baseDate.plusDays(1);
            dateReason = "tomorrow";
        } else if (containsAny(text, "后天")) {
            baseDate = baseDate.plusDays(2);
            dateReason = "day_after_tomorrow";
        } else if (containsAny(text, "下周")) {
            // 下周一 10:00（这里用 10:00/9:00都行）
            baseDate = baseDate.with(java.time.DayOfWeek.MONDAY).plusWeeks(1);
            dateReason = "next_week_monday";
        } else if (containsAny(text, "下个月")) {
            baseDate = baseDate.plusMonths(1).withDayOfMonth(1);
            dateReason = "next_month_day1";
        }

        // 时段粒度
        LocalTime time = MORNING;
        String timeReason = "morning_09:00";

        if (containsAny(text, "中午", "午饭", "午餐")) {
            time = NOON;
            timeReason = "noon_12:00";
        } else if (containsAny(text, "下午")) {
            time = AFTERNOON;
            timeReason = "afternoon_15:00";
        } else if (containsAny(text, "晚上", "夜里", "夜晚")) {
            time = EVENING;
            timeReason = "evening_20:00";
        } else if (containsAny(text, "早上", "上午")) {
            time = MORNING;
            timeReason = "morning_09:00";
        }

        // 特例：如果用户说“等一下/一会儿/稍后”，但又没给分钟
        // 先锚定为 now + 10min（可改成 5）
        if (containsAny(text, "等一下", "一会", "稍后") && !containsAny(text, "分钟", "min")) {
            LocalDateTime t = now.plusMinutes(10);
            out.setEventTime(t);
            out.setAssumed(true);
            out.setReason("fuzzy_assumed:soon_now+10m");
            return out;
        }

        LocalDateTime assumedEventTime = LocalDateTime.of(baseDate, time);
        out.setEventTime(assumedEventTime);
        out.setAssumed(true);
        out.setReason("fuzzy_assumed:" + dateReason + "+" + timeReason);
        return out;
    }

    private static boolean containsAny(String text, String... keys) {
        if (text == null) return false;
        for (String k : keys) {
            if (text.contains(k)) return true;
        }
        return false;
    }

    private static LocalDateTime parseEventTime(EventParseResult r) {
        if (r == null) return null;
        String s = r.getEventTime();
        if (s == null || s.trim().isEmpty()) return null;

        // 你现在的格式示例： "2026-01-31 10:55:35"
        try {
            return LocalDateTime.parse(s.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception ignore) {
            // 如果未来 LLM 返回改格式，这里可以扩展
            return null;
        }
    }
}

package com.fly.forgotyet.service;

import com.fly.forgotyet.enums.TriggerBucket;

import java.time.Duration;

public final class TriggerBucketUtil {
    private TriggerBucketUtil() {}

    public static Duration toDuration(TriggerBucket b) {
        return switch (b) {
            case M0 -> Duration.ZERO;
            case M1 -> Duration.ofMinutes(1);
            case M5 -> Duration.ofMinutes(5);
            case M10 -> Duration.ofMinutes(10);
            case M15 -> Duration.ofMinutes(15);
            case M30 -> Duration.ofMinutes(30);

            case H1 -> Duration.ofHours(1);
            case H2 -> Duration.ofHours(2);
            case H4 -> Duration.ofHours(4);
            case H8 -> Duration.ofHours(8);

            case D1 -> Duration.ofDays(1);
            case D3 -> Duration.ofDays(3);
            case D7 -> Duration.ofDays(7);
            case D14 -> Duration.ofDays(14);
            case D30 -> Duration.ofDays(30);
        };
    }
}

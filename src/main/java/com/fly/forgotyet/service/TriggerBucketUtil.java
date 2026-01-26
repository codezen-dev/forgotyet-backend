package com.fly.forgotyet.service;

import com.fly.forgotyet.entity.TriggerBucket;

import java.time.Duration;

public final class TriggerBucketUtil {
    private TriggerBucketUtil() {}

    public static Duration toDuration(TriggerBucket b) {
        return switch (b) {
            case M0 -> Duration.ZERO;
            case M15 -> Duration.ofMinutes(15);
            case H1 -> Duration.ofHours(1);
            case H4 -> Duration.ofHours(4);
            case D1 -> Duration.ofDays(1);
            case D3 -> Duration.ofDays(3);
        };
    }
}

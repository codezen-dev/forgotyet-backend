package com.fly.forgotyet.entity;

import com.fly.forgotyet.enums.Complexity;
import com.fly.forgotyet.enums.TriggerBucket;
import com.fly.forgotyet.enums.TriggerIntent;
import lombok.Data;

import java.time.Instant;

@Data
public class TriggerPlan {
    private Instant eventTime;
    private Instant triggerTime;

    private TriggerIntent intent;
    private Complexity complexity;
    private TriggerBucket bucket;

    // debug / 回放
    private boolean forcedImmediate;
    private boolean fallbackNowPlus1m;
    private String reason;
}

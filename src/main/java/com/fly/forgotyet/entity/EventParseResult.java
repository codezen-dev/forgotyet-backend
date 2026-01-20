package com.fly.forgotyet.entity;

import lombok.Data;

@Data
public class EventParseResult {
    // 提取出的时间 (yyyy-MM-dd HH:mm:ss)
    private String eventTime;
    // 简短摘要
    private String summary;
    // 是否有效
    private boolean valid;
}
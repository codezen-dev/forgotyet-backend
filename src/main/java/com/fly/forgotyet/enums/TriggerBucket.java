package com.fly.forgotyet.enums;

/**
 * 提前量 bucket（从“更晚”到“更早”可做有序移动）
 */
public enum TriggerBucket {

    // minutes
    M0,   // 0m
    M1,   // 1m
    M5,   // 5m
    M10,  // 10m
    M15,  // 15m
    M30,  // 30m

    // hours
    H1,   // 1h
    H2,   // 2h
    H4,   // 4h
    H8,   // 8h

    // days
    D1,   // 1d
    D3,   // 3d
    D7,   // 7d
    D14,  // 14d
    D30   // 30d (≈1 month)
}

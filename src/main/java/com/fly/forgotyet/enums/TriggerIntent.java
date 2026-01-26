package com.fly.forgotyet.enums;

public enum TriggerIntent {
    /** 到点提醒（1分钟后 / 5分钟后） */
    AT_TIME,

    /** 情绪兜底型（交报告 / 忘了就完了的事） */
    CUSHION,

    /** 需要准备的提醒（出游 / 体检 / 面试） */
    PREPARE
}


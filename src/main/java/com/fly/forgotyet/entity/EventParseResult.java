package com.fly.forgotyet.entity;

import com.fly.forgotyet.enums.Complexity;
import com.fly.forgotyet.enums.TriggerIntent;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class EventParseResult {

    /** 事件标题 / 摘要 */
    private String summary;

    /** 用户表达的“事实时间”（ISO 字符串） */
    private String eventTime;

    /** LLM 是否认为这是一个有效事件 */
    private boolean valid;

    /* ================= 新增：Trigger 策略字段 ================= */

    /** 提醒意图 */
    private TriggerIntent intent;
    // AT_TIME / CUSHION / PREPARE

    /** 复杂度评估 */
    private Complexity complexity;
    // LOW / MEDIUM / HIGH

    /** 是否需要准备 */
    private boolean prepRequired;

    /** 依赖项：人 / 票 / 物料 等 */
    private List<String> dependencies;

    /** 是否属于“分钟级 / 立即”强规则 */
    private boolean urgentMinuteLevel;

    /* ================= 可选：debug / 回放 ================= */

    /** 原始用户输入（可选） */
    private String rawUserText;

    /** LLM 额外信息（置信度、标签等） */
    private Map<String, Object> llmMeta;
}

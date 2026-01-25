package com.fly.forgotyet.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fly.forgotyet.entity.EventParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final ConfigService configService;

    // 🚀 主模型配置 (如 DeepSeek)
    @Value("${llm.primary.api-key}")
    private String primaryApiKey;
    @Value("${llm.primary.base-url}")
    private String primaryBaseUrl;
    @Value("${llm.primary.model}")
    private String primaryModelName;

    // 🚀 备用模型配置 (如 通义千问/Kimi)
    @Value("${llm.backup.api-key}")
    private String backupApiKey;
    @Value("${llm.backup.base-url}")
    private String backupBaseUrl;
    @Value("${llm.backup.model}")
    private String backupModelName;

    /**
     * 解析用户输入 -> 结构化数据 (高可用版)
     */
    /**
     * 解析用户输入 -> 结构化数据 (高可用版)
     */
    public EventParseResult parseInput(String userInput) {
        log.info(">>> [主模型] 正在解析: {}", userInput);

        // 🚀 1. 获取精确到秒的当前时间
        String nowStr = DateUtil.now();

        // 🚀 2. 获取今天是星期几 (中文，例如：星期日)
        String dayOfWeek = cn.hutool.core.date.DateUtil.dayOfWeekEnum(new java.util.Date()).toChinese("星期");

        // 🚀 3. 组合出最强的防穿越时间锚点
        String absoluteTimeContext = nowStr + " " + dayOfWeek;

        String systemPromptTemplate = configService.getPrompt("prompt.parser.system", "");

        // 🚀 4. 在原有的 Prompt 基础上，强行追加“严禁穿越”规则
        String systemPrompt = systemPromptTemplate.replace("{currentTime}", absoluteTimeContext)
                + "\n\n【系统最高指令：当前时间是 " + absoluteTimeContext + "，你生成的 trigger_time 绝不能早于这个时间！如果是5分钟后，必须在这个时间基础上加5分钟！】";

        try {
            return callParserApi(primaryBaseUrl, primaryApiKey, primaryModelName, systemPrompt, userInput);
        } catch (Exception e) {
            log.warn("⚠️ [主模型] 解析超时或崩溃，触发熔断，秒级切换至备用模型... 错误: {}", e.getMessage());
            try {
                return callParserApi(backupBaseUrl, backupApiKey, backupModelName, systemPrompt, userInput);
            } catch (Exception backupEx) {
                log.error("❌ [备用模型] 也已崩溃", backupEx);
                EventParseResult fallback = new EventParseResult();
                fallback.setValid(false);
                return fallback;
            }
        }
    }

    /**
     * 根据用户原话，生成安抚邮件内容 (高可用版)
     */
    public String generateEmailContent(String rawInput) {
        String systemPromptTemplate = configService.getPrompt("prompt.soother.system", "");

        try {
            log.info(">>> [主模型] 正在生成安抚文案...");
            return callSootherApi(primaryBaseUrl, primaryApiKey, primaryModelName, systemPromptTemplate, rawInput);
        } catch (Exception e) {
            log.warn("⚠️ [主模型] 文案生成失败，切换至备用模型... 错误: {}", e.getMessage());
            try {
                return callSootherApi(backupBaseUrl, backupApiKey, backupModelName, systemPromptTemplate, rawInput);
            } catch (Exception backupEx) {
                log.error("❌ [备用模型] 也已崩溃", backupEx);
                // 终极兜底：返回标准模板，绝不阻断邮件发送
                return "（系统自动提醒）您之前提到的事情快到时间了，别忘了：" + rawInput;
            }
        }
    }

    // ================== 底层调用抽离 ==================

    private EventParseResult callParserApi(String url, String apiKey, String model, String sysPrompt, String userInput) {
        JSONObject requestBody = new JSONObject();
        requestBody.set("model", model);
        requestBody.set("response_format", new JSONObject().set("type", "json_object"));

        JSONArray messages = new JSONArray();
        messages.add(new JSONObject().set("role", "system").set("content", sysPrompt));
        messages.add(new JSONObject().set("role", "user").set("content", userInput));
        requestBody.set("messages", messages);

        HttpResponse response = HttpRequest.post(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody.toString())
                .timeout(8000) // 🚀 硬超时改为 8 秒，防止卡死
                .execute();

        String body = response.body();
        JSONObject jsonResponse = JSONUtil.parseObj(body);
        String content = jsonResponse.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getStr("content");

        // 🚀 1. 新增：洗掉大模型可能附带的 Markdown 标签
        String cleanJson = content.replace("```json", "").replace("```", "").trim();

        log.info(">>> [大模型原始返回]: {}", cleanJson);

        // 🚀 2. 核心修改：用全路径调用咱们自己写的 JsonUtil，彻底解决时区问题！
        return com.fly.forgotyet.common.JsonUtil.toBean(cleanJson, EventParseResult.class);
    }

    private String callSootherApi(String url, String apiKey, String model, String sysPrompt, String rawInput) {
        JSONObject requestBody = new JSONObject();
        requestBody.set("model", model);

        JSONArray messages = new JSONArray();
        messages.add(new JSONObject().set("role", "system").set("content", sysPrompt));
        messages.add(new JSONObject().set("role", "user").set("content", "用户的原话是：" + rawInput + "。请生成一段简短的安抚提醒。"));
        requestBody.set("messages", messages);

        HttpResponse response = HttpRequest.post(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(requestBody.toString())
                .timeout(8000) // 🚀 硬超时 8 秒
                .execute();

        JSONObject jsonResponse = JSONUtil.parseObj(response.body());
        return jsonResponse.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getStr("content");
    }
}

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

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${llm.base-url}")
    private String baseUrl;

    @Value("${llm.model}")
    private String modelName;

    /**
     * 解析用户输入 -> 结构化数据
     */
    public EventParseResult parseInput(String userInput) {
        // 1. 获取当前时间
        String nowStr = DateUtil.now();

        // 2. 获取 Prompt 模板 (从数据库热加载)
        String systemPromptTemplate = configService.getPrompt("prompt.parser.system", "");
        // 替换变量
        String systemPrompt = systemPromptTemplate.replace("{currentTime}", nowStr);

        // 3. 构造请求体 (OpenAI 兼容格式)
        JSONObject requestBody = new JSONObject();
        requestBody.set("model", modelName);
        requestBody.set("response_format", new JSONObject().set("type", "json_object")); // 强制 JSON 模式

        JSONArray messages = new JSONArray();
        messages.add(new JSONObject().set("role", "system").set("content", systemPrompt));
        messages.add(new JSONObject().set("role", "user").set("content", userInput));
        requestBody.set("messages", messages);

        try {
            // 4. 发起请求
            log.info(">>> 正在请求 LLM 解析: {}", userInput);
            HttpResponse response = HttpRequest.post(baseUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(requestBody.toString())
                    .timeout(20000) // 20秒超时
                    .execute();

            String body = response.body();
            log.debug("LLM Response: {}", body);

            // 5. 解析响应
            JSONObject jsonResponse = JSONUtil.parseObj(body);
            String content = jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getStr("content");

            // 6. 转为对象
            return JSONUtil.toBean(content, EventParseResult.class);

        } catch (Exception e) {
            log.error("调用 LLM 失败", e);
            // 兜底：返回无效
            EventParseResult fallback = new EventParseResult();
            fallback.setValid(false);
            return fallback;
        }
    }

    /**
     * 根据用户原话，生成安抚邮件内容
     */
    public String generateEmailContent(String rawInput) {
        // 1. 获取安抚 Prompt (从数据库热加载)
        String systemPromptTemplate = configService.getPrompt("prompt.soother.system", "");
        // 这里暂时没有变量需要替换，如果有 {raw_input} 可以在 prompt 里处理，或者直接拼在 user message 里

        // 2. 构造请求
        JSONObject requestBody = new JSONObject();
        requestBody.set("model", modelName);
        // 注意：这次我们不需要 JSON 格式，要纯文本，所以不要加 response_format: json_object

        JSONArray messages = new JSONArray();
        messages.add(new JSONObject().set("role", "system").set("content", systemPromptTemplate));
        // 我们把用户的原话发给 AI，让它基于此生成回复
        messages.add(new JSONObject().set("role", "user").set("content", "用户的原话是：" + rawInput + "。请生成一段简短的安抚提醒。"));
        requestBody.set("messages", messages);

        try {
            HttpResponse response = HttpRequest.post(baseUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(requestBody.toString())
                    .timeout(30000)
                    .execute();

            JSONObject jsonResponse = JSONUtil.parseObj(response.body());
            // 提取内容
            return jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getStr("content");

        } catch (Exception e) {
            log.error("生成邮件内容失败", e);
            return "（系统自动提醒）您之前提到的事情快到时间了，别忘了：" + rawInput;
        }
    }
}
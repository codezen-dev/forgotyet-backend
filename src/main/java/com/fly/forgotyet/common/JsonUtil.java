package com.fly.forgotyet.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JsonUtil {

    // 🚀 1. 全局单例，极其节省内存，且绝对线程安全
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static {
        // 🚀 2. 忽略未知字段：大模型乱加字段也不会报错
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 🚀 3. 支持 Java 8 时间 (极其重要，防止 LocalDateTime 序列化报错)
        MAPPER.registerModule(new JavaTimeModule());
    }

    /**
     * 将 JSON 字符串反序列化为 Java 对象
     */
    public static <T> T toBean(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            log.error("❌ JSON 反序列化失败: {}", json, e);
            throw new RuntimeException("JSON 解析异常");
        }
    }

    /**
     * 将 Java 对象序列化为 JSON 字符串
     */
    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("❌ JSON 序列化失败", e);
            throw new RuntimeException("JSON 序列化异常");
        }
    }

}

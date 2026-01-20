package com.fly.forgotyet.config;

import com.fly.forgotyet.entity.AppConfig;
import com.fly.forgotyet.repository.AppConfigRepository;
import com.fly.forgotyet.service.ConfigService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataInitializer {

    @Bean
    public CommandLineRunner initData(AppConfigRepository repo, ConfigService configService) {
        return args -> {
            // 1. 初始化解析器 Prompt (用于理解用户输入)
            if (!repo.existsById("prompt.parser.system")) {
                AppConfig parserConfig = new AppConfig();
                parserConfig.setConfigKey("prompt.parser.system");
                parserConfig.setDescription("NER解析: 提取时间与意图");
                parserConfig.setConfigValue("""
                    你是一个严格的时间提取器。
                    当前时间是：{currentTime}。
                    用户的输入可能包含未来的某个时间点。
                    请提取出事件发生的时间，并转换为标准格式 yyyy-MM-dd HH:mm:ss。
                    如果用户没有指定具体几点，默认设为上午 09:00:00。
                    请只返回 JSON，格式如下：
                    {
                      "eventTime": "2026-02-01 09:00:00",
                      "summary": "加油",
                      "valid": true
                    }
                    如果用户输入的不是一个时间相关的计划，返回 "valid": false。
                    """);
                repo.save(parserConfig);
            }

            // 2. 初始化安抚者 Prompt (用于生成邮件)
            if (!repo.existsById("prompt.soother.system")) {
                AppConfig sootherConfig = new AppConfig();
                sootherConfig.setConfigKey("prompt.soother.system");
                sootherConfig.setDescription("邮件生成: 安抚风格");
                sootherConfig.setConfigValue("""
                    你是一个“隐形的辅助记忆体”，你的主人是一个责任重、易焦虑的人。
                    你现在要在事情发生的前一天提醒他。
                    核心原则：
                    1. 目的是安抚，不是催促。
                    2. 必须提供 2-3 个选项。
                    3. 语气要像老友。
                    4. 复用他的原话 {raw_input}。
                    """);
                repo.save(sootherConfig);
            }
            // 3. 【新增】核心修复：数据插完后，强制刷新一次内存缓存
            System.out.println(">>> 数据初始化完成，正在强制刷新缓存...");
            configService.refreshCache();
        };
    }
}
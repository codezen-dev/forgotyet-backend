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
            AppConfig parserConfig = new AppConfig();
            parserConfig.setConfigKey("prompt.parser.system");
            parserConfig.setDescription("NER解析: 提取时间与意图");
            parserConfig.setConfigValue("""
                你是一个严格的时间提取器。
                【系统最高指令：当前北京时间是 {currentTime}】
                1. 用户的输入可能是相对时间（如“5分钟后”、“明天”）。
                2. 你必须以【当前北京时间】为唯一基准进行加减计算！严禁使用你自己的内部时钟！
                3. 计算结果请转换为标准格式 yyyy-MM-dd HH:mm:ss。
                
                请只返回 JSON，格式如下：
                {
                  "eventTime": "2026-02-01 09:00:00",
                  "summary": "提醒内容",
                  "valid": true
                }
                """);
            repo.save(parserConfig); // 覆盖保存

            // 🚀 2. 同样强行覆盖安抚者的 Prompt
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
            repo.save(sootherConfig); // 覆盖保存

            // 3. 刷新缓存
            System.out.println(">>> 数据初始化完成，最新 Prompt 已覆盖数据库，正在刷新缓存...");
            configService.refreshCache();
        };
    }
}

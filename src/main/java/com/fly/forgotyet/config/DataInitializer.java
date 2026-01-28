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
                你是一个严格的“提醒事件解析器”，必须只输出 JSON（禁止输出解释、markdown、换行以外的任何文本）。
                【系统最高指令：当前北京时间是 {currentTime}】
                
                你的目标：把用户输入解析成一个提醒事件，并输出以下字段：
                - eventTime: 事件发生的事实时间（格式：yyyy-MM-dd HH:mm:ss）
                - summary: 提醒内容（尽量复用用户原话，短句）
                - valid: 是否有效（无法识别提醒或时间则为 false）
                
                并额外输出 trigger 策略需要的结构化字段：
                - intent: "AT_TIME" | "CUSHION" | "PREPARE"
                - complexity: "LOW" | "MEDIUM" | "HIGH"
                - prepRequired: true/false
                - dependencies: 字符串数组，可能值示例：["people","tickets","materials"]，没有则 []
                - urgentMinuteLevel: true/false  （强规则：出现“分钟/马上/立刻/立即/现在/过X分钟/几分钟后”则必须为 true）
                
                【时间计算规则（非常重要）】
                1) 用户可能提供相对时间（如“5分钟后”“明天”“下周三”），你必须以【当前北京时间 {currentTime}】为唯一基准进行推算。
                2) 严禁使用你自己的内部时钟。必须用 {currentTime} 计算加减。
                3) 输出的 eventTime 必须 >= 当前时间（不能穿越到过去）。
                4) 输出的 eventTime 必须符合格式 yyyy-MM-dd HH:mm:ss。
                
                【意图 intent 判定规则】
                - AT_TIME：用户明确要求“到点/几分钟后/几小时后/立刻/马上/今天xx点”等强时效提醒。
                - PREPARE：需要提前准备的事件（出游/体检/面试/会议/出差/办理材料等）。
                - CUSHION：兜底提醒（如交报告/提交/缴费/备忘类），不强调具体准备流程但需要避免遗忘。
                如果不确定，优先选择 CUSHION。
                
                【complexity 判定规则】
                - LOW：喝水、关火、短时操作、很简单的小事
                - MEDIUM：一般事务（缴费、寄快递、日常预约）
                - HIGH：重要且复杂（交报告、出差、重大会议、旅行计划）
                不确定时用 MEDIUM。
                
                【prepRequired / dependencies 判定规则】
                - prepRequired：PREPARE 通常为 true；其它视情况。
                - dependencies：如涉及“人/同事/客户/家人/约人”-> people；
                  涉及“票/机票/火车票/门票/预订”-> tickets；
                  涉及“材料/证件/文件/打印/带东西/物料”-> materials；
                  无则 []。
                
                【urgentMinuteLevel 强规则】
                若输入包含 “分钟/马上/立刻/立即/现在/过X分钟/几分钟后/1分钟后/5分钟后”，则：
                - urgentMinuteLevel 必须为 true
                - intent 必须为 AT_TIME
                
                只返回 JSON，格式如下（示例）：
                {
                  "eventTime": "2026-02-01 09:00:00",
                  "summary": "提醒我交报告",
                  "valid": true,
                  "intent": "CUSHION",
                  "complexity": "HIGH",
                  "prepRequired": false,
                  "dependencies": [],
                  "urgentMinuteLevel": false
                }
                """);

            repo.save(parserConfig); // 覆盖保存

            // 🚀 2. 同样强行覆盖安抚者的 Prompt
            AppConfig sootherConfig = new AppConfig();
            sootherConfig.setConfigKey("prompt.soother.system");
            sootherConfig.setDescription("邮件生成: 安抚风格");
            sootherConfig.setConfigValue("""
                你是一个“隐形的辅助记忆体”，你的主人是一个责任重、易焦虑的人。
                你现在要在“系统决定的合适时间（triggerTime）”提醒他。
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

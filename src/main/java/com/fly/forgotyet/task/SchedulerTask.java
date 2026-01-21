package com.fly.forgotyet.task;

import com.fly.forgotyet.entity.Event;
import com.fly.forgotyet.repository.EventRepository;
import com.fly.forgotyet.service.EmailService;
import com.fly.forgotyet.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SchedulerTask {

    private final EventRepository eventRepository;
    private final LlmService llmService;
    private final EmailService emailService;

    // 每分钟执行一次 (MVP 阶段频率高一点方便测试，生产环境可以 10 分钟或 1 小时)
    @Scheduled(cron = "0 */1 * * * ?")
    public void scanEvents() {
        log.debug(">>> 心跳检测: 正在扫描到期事件...");

        // 1. 查库：找 "状态是沉默" 且 "触发时间 <= 当前时间" 的事件
        List<Event> events = eventRepository.findByStatusAndTriggerTimeBefore("SILENT", LocalDateTime.now());

        if (events.isEmpty()) {
            return;
        }

        log.info(">>> 发现 {} 个到期事件，准备触发...", events.size());

        for (Event event : events) {
            try {
                processEvent(event);
            } catch (Exception e) {
                log.error("事件处理失败: ID=" + event.getId(), e);
            }
        }
    }

    private void processEvent(Event event) {
        // 2. 生成 AI 文案
        String content = llmService.generateEmailContent(event.getRawInput());

        // 3. 发送邮件
        String subject = "关于你之前提到的那件事..."; // 标题要克制，不要写 "紧急提醒！"
        emailService.sendSimpleEmail(event.getUserEmail(), subject, content);

        // 4. 更新状态 (防止重复发送)
        event.setStatus("DELIVERED");
        eventRepository.save(event);

        log.info(">>> 事件 ID={} 已触达并归档。", event.getId());
    }
}
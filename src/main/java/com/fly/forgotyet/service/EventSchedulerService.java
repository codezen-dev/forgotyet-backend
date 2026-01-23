package com.fly.forgotyet.service;

import com.fly.forgotyet.entity.Event;
import com.fly.forgotyet.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventSchedulerService {

    private final TaskScheduler taskScheduler;
    private final EventRepository eventRepository;
    private final LlmService llmService;     // 注入你现有的 LLM 服务
    private final EmailService emailService; // 注入你现有的邮件服务

    // 用于管理内存中的任务，防止重复或取消
    private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * 🚀 1. 开机自愈：系统启动时，把未来所有的 SILENT 任务挂载到内存
     */
    @PostConstruct
    public void recoverSilentTasksOnBoot() {
        log.info("🔄 [系统自检] 开始恢复未来待触发的 SILENT 定时任务...");

        // 获取所有在当前时间之后触发的静默任务
        List<Event> futureEvents = eventRepository.findByStatusAndTriggerTimeAfter("SILENT", LocalDateTime.now());

        if (futureEvents.isEmpty()) {
            log.info("✅ [系统自检] 无待恢复任务。");
            return;
        }

        log.info("⚠️ [系统自检] 发现 {} 个由于重启脱离内存的 SILENT 任务，正在重新挂载...", futureEvents.size());

        for (Event event : futureEvents) {
            scheduleEvent(event);
        }

        log.info("✅ [系统自检] 任务恢复完毕！");
    }

    /**
     * 🚀 2. 核心调度：将任务精准挂载到内存时钟
     */
    public void scheduleEvent(Event event) {
        // 转换 triggerTime 为底层时间戳
        Instant targetInstant = event.getTriggerTime().atZone(ZoneId.systemDefault()).toInstant();

        // 防止重复挂载
        if (scheduledTasks.containsKey(event.getId())) {
            return;
        }

        ScheduledFuture<?> future = taskScheduler.schedule(() -> {
            log.info("⏰ 【ForgotYet 触发】任务 ID [{}]: {}", event.getId(), event.getRawInput());

            try {
                // 1. 调用你原有的 AI 生成安抚文案逻辑
                String content = llmService.generateEmailContent(event.getRawInput());

                // 2. 调用你原有的邮件发送逻辑
                String subject = "关于你之前提到的那件事...";
                emailService.sendSimpleEmail(event.getUserEmail(), subject, content);

                // 3. 状态流转并落库
                event.setStatus("DELIVERED");
                eventRepository.save(event);

            } catch (Exception e) {
                log.error("❌ 事件处理失败 ID=" + event.getId(), e);
            } finally {
                // 4. 清理内存
                scheduledTasks.remove(event.getId());
            }

        }, targetInstant);

        scheduledTasks.put(event.getId(), future);
        log.debug("📌 任务 [ID:{}] 已精准挂载，将在 {} 触发", event.getId(), event.getTriggerTime());
    }
}
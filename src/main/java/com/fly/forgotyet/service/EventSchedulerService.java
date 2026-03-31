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
    private static final String STATUS_SILENT = "SILENT";
    private static final String STATUS_DELIVERED = "DELIVERED";
    private static final String STATUS_CANCELED = "CANCELED";

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

        if (event.getTriggerTime() == null) {
            log.warn("skip schedule: triggerTime is null, id={}", event.getId());
            return;
        }
        if (!"SILENT".equalsIgnoreCase(event.getStatus())) {
            // 只调度 SILENT，其他状态（PENDING/DELIVERED/CANCELED）跳过
            return;
        }

        // 转换 triggerTime 为底层时间戳
        Instant targetInstant = event.getTriggerTime().atZone(ZoneId.systemDefault()).toInstant();

        // 防止重复挂载
        if (scheduledTasks.containsKey(event.getId())) {
            return;
        }

        ScheduledFuture<?> future = taskScheduler.schedule(() -> {

            // ✅ 兜底：执行前再查一次 DB（保证取消后绝不误发）
            Event latest = eventRepository.findById(event.getId()).orElse(null);
            if (latest == null) {
                scheduledTasks.remove(event.getId());
                return;
            }

            if (STATUS_CANCELED.equalsIgnoreCase(latest.getStatus())) {
                log.info("⛔ 事件已取消，跳过触发 ID={}", event.getId());
                scheduledTasks.remove(event.getId());
                return;
            }

            log.info("⏰ 【ForgotYet 触发】任务 ID [{}]: {}", latest.getId(), latest.getRawInput());

            try {
                String content = llmService.generateEmailContent(latest.getRawInput());
                String subject = "关于你之前提到的那件事...";
                emailService.sendSimpleEmail(latest.getUserEmail(), subject, content);

                latest.setStatus(STATUS_DELIVERED);
                eventRepository.save(latest);

            } catch (Exception e) {
                log.error("❌ 事件处理失败 ID=" + latest.getId(), e);
            } finally {
                scheduledTasks.remove(latest.getId());
            }

        }, targetInstant);

        scheduledTasks.put(event.getId(), future);
        log.debug("📌 任务 [ID:{}] 已精准挂载，将在 {} 触发", event.getId(), event.getTriggerTime());
    }

    public boolean cancelScheduled(Long eventId) {
        ScheduledFuture<?> future = scheduledTasks.remove(eventId);
        if (future != null) {
            boolean canceled = future.cancel(false);
            log.info("🛑 尝试取消内存任务 ID={}, result={}", eventId, canceled);
            return canceled;
        }
        return false;
    }

}

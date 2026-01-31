package com.fly.forgotyet.service;

import cn.hutool.core.date.LocalDateTimeUtil;
import com.fly.forgotyet.common.JsonUtil;
import com.fly.forgotyet.entity.Event;
import com.fly.forgotyet.entity.EventParseResult;
import com.fly.forgotyet.entity.TriggerPlan;
import com.fly.forgotyet.enums.TriggerFeedback;
import com.fly.forgotyet.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final LlmService llmService;
    private final EventRepository eventRepository;
    private final EventSchedulerService eventSchedulerService;
    private final TriggerPlanService triggerPlanService;


    /**
     * 🚀 接收用户输入，保存为未来的事件 (异步处理)
     * 加入 @Async 后，该方法会在名为 task-X 的子线程中执行，前端瞬间得到响应。
     */
    @Async
    public void createEvent(String rawInput, String userEmail) {
        log.info("🧵 [异步线程] 开始处理任务: {}", rawInput);

        EventParseResult parseResult = llmService.parseInput(rawInput);
        if (!parseResult.isValid()) {
            log.warn("无效的输入，AI 拒绝处理: {}", rawInput);
            return;
        }

        Event event = new Event();
        event.setRawInput(rawInput);
        event.setUserEmail(userEmail);
        event.setStatus("SILENT");
        event.setCreateTime(LocalDateTime.now());

        // 1️⃣ 事实时间（无时区，来自用户语义）
        LocalDateTime eventTime = LocalDateTimeUtil.parse(
                parseResult.getEventTime(),
                "yyyy-MM-dd HH:mm:ss"
        );
        event.setEventTime(eventTime);

        // 2️⃣ 明确时区边界（MVP：系统默认）
        ZoneId zoneId = ZoneId.systemDefault();
        Instant eventInstant = eventTime.atZone(zoneId).toInstant();

        // 3️⃣ 策略层：只处理 Instant
        TriggerPlan plan = triggerPlanService.plan(userEmail, parseResult, eventInstant);

        // 4️⃣ 策略结果回到 LocalDateTime（用于 DB / Scheduler）
        LocalDateTime triggerTime =
                LocalDateTime.ofInstant(plan.getTriggerTime(), zoneId);
        event.setTriggerTime(triggerTime);

        // （可选）回放字段
        event.setTriggerBucket(plan.getBucket() == null ? null : plan.getBucket().name());
        event.setTriggerIntent(plan.getIntent() == null ? null : plan.getIntent().name());
        event.setComplexity(plan.getComplexity() == null ? null : plan.getComplexity().name());
        event.setPrepRequired(parseResult.isPrepRequired());
        event.setDependenciesJson(
                parseResult.getDependencies() == null ? null : JsonUtil.toJson(parseResult.getDependencies())
        );
        event.setTriggerReason(plan.getReason());

        // 5️⃣ 落库
        eventRepository.save(event);
        log.info("新事件已存储: ID={}, 触发时间={}", event.getId(), event.getTriggerTime());

        // 6️⃣ 精准挂载
        eventSchedulerService.scheduleEvent(event);
    }

    /**
     * V1：最近事件列表
     */
    public List<Event> listRecentEvents(String userEmail, int limit) {
        int size = Math.max(1, Math.min(limit, 50)); // 防滥用：1~50
        return eventRepository
                .findByUserEmailOrderByCreateTimeDesc(
                        userEmail,
                        PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createTime"))
                )
                .getContent();
    }

    /**
     * V1：提交反馈（早/好/晚）
     */
    public void submitFeedback(String userEmail, Long eventId, TriggerFeedback feedback) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("事件不存在"));

        if (!userEmail.equals(event.getUserEmail())) {
            throw new RuntimeException("无权限操作该事件");
        }

        event.setFeedback(feedback);
        eventRepository.save(event);
    }

}

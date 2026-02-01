package com.fly.forgotyet.service;

import cn.hutool.core.date.LocalDateTimeUtil;
import com.fly.forgotyet.common.JsonUtil;
import com.fly.forgotyet.entity.Event;
import com.fly.forgotyet.entity.EventParseResult;
import com.fly.forgotyet.entity.TriggerPlan;
import com.fly.forgotyet.enums.TriggerFeedback;
import com.fly.forgotyet.repository.EventRepository;
import com.fly.forgotyet.utils.EventTimeNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;


@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final LlmService llmService;
    private final EventRepository eventRepository;
    private final EventSchedulerService eventSchedulerService;
    private final TriggerPlanService triggerPlanService;
    private final Clock clock;



    /**
     * 🚀 接收用户输入，保存为未来的事件 (异步处理)
     * 加入 @Async 后，该方法会在名为 task-X 的子线程中执行，前端瞬间得到响应。
     */
    @Async
    public void createEvent(String rawInput, String email) {
        log.info("🧵 [异步线程] 开始处理任务: {}", rawInput);

        // 1) LLM parse
        EventParseResult r = llmService.parseInput(rawInput);
        if (r == null || !Boolean.TRUE.equals(r.isValid())) {
            log.warn("❌ 解析失败或无效输入: {}", rawInput);
            return;
        }

        // 2) C3-1: 模糊时间兜底（关键新增）
        EventTimeNormalizer.Result normalized = EventTimeNormalizer.normalize(r, rawInput, clock);
        LocalDateTime normalizedEventTime = normalized.getEventTime(); // 一定不为空
        Instant eventInstant = normalizedEventTime.atZone(ZoneId.systemDefault()).toInstant();

        // 3) 触发计划（你原来的 TriggerPlanService）
        TriggerPlan plan = triggerPlanService.plan(email, r, eventInstant);

        // 4) 落库（注意：Event.eventTime / triggerTime 是 LocalDateTime）
        Event event = new Event();
        event.setRawInput(rawInput);
        event.setUserEmail(email);

        event.setEventTime(normalizedEventTime);
        event.setTriggerTime(LocalDateTime.ofInstant(plan.getTriggerTime(), ZoneId.systemDefault()));

        // 状态：保持你现在的 SILENT -> DELIVERED 流转
        event.setStatus("SILENT");

        // 回放字段（你原先已经有这些字段的话就保留）
        if (plan.getBucket() != null) event.setTriggerBucket(plan.getBucket().name());
        if (plan.getIntent() != null) event.setTriggerIntent(plan.getIntent().name());
        if (plan.getComplexity() != null) event.setComplexity(plan.getComplexity().name());
        event.setPrepRequired(r.isPrepRequired());

        // dependenciesJson：你原来怎么存就怎么存（下面给一个安全兜底）
        // 如果你已经有 ObjectMapper，就用你现有的
        try {
            if (r.getDependencies() != null) {
                event.setDependenciesJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(r.getDependencies()));
            }
        } catch (Exception ignore) {
            event.setDependenciesJson("[]");
        }

        // triggerReason：把“时间兜底原因”拼进去（便于你前端展示/调试）
        String timeReason = normalized.isAssumed()
                ? normalized.getReason()
                : "llm_eventTime_used";

        // 你 plan 里本来就有 reason（bucket 选择原因）
        String fullReason = timeReason + " | " + Optional.ofNullable(plan.getReason()).orElse("");
        event.setTriggerReason(fullReason);

        // feedback 初始为空即可（默认 null）
        Event saved = eventRepository.save(event);

        log.info("新事件已存储: ID={}, 触发时间={}", saved.getId(), saved.getTriggerTime());

        // 5) 精准挂载任务（你现在 EventSchedulerService 已有）
        eventSchedulerService.scheduleEvent(saved);
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
        log.info("🧠 feedback recorded: user={}, eventId={}, feedback={}", userEmail, eventId, feedback);
    }

    @Transactional
    public void cancelEvent(Long eventId, String userEmail) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("事件不存在"));

        if (!userEmail.equals(event.getUserEmail())) {
            throw new RuntimeException("无权限取消该事件");
        }

        // 幂等：重复取消不报错
        if ("CANCELED".equalsIgnoreCase(event.getStatus())) {
            return;
        }

        event.setStatus("CANCELED");
        eventRepository.save(event);

        // 尝试取消内存任务（成功最好，失败也没关系：执行前会查 DB 状态兜底）
        eventSchedulerService.cancelScheduled(eventId);
    }


}

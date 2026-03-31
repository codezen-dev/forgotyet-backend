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
            log.warn("🫴 解析不确定，进入 PENDING: {}", rawInput);

            Event pending = new Event();
            pending.setRawInput(rawInput);
            pending.setUserEmail(email);

            pending.setStatus("PENDING");   // 核心
            pending.setEventTime(null);
            pending.setTriggerTime(null);

            pending.setTriggerReason("pending: parse_invalid_or_uncertain");

            // 尽量保留信息（即使不完整）
            if (r != null) {
                if (r.getIntent() != null) pending.setTriggerIntent(r.getIntent().name());
                if (r.getComplexity() != null) pending.setComplexity(r.getComplexity().name());
                pending.setPrepRequired(r.isPrepRequired());
            }

            eventRepository.save(pending);
            return;
        }

        // 2) C3-1: 模糊时间兜底（关键新增）
        EventTimeNormalizer.Result normalized = EventTimeNormalizer.normalize(r, rawInput, clock);
        LocalDateTime normalizedEventTime = normalized.getEventTime(); // 一定不为空
        // ✅ PENDING：接住，但不调度
        if (normalizedEventTime == null) {
            Event event = new Event();
            event.setRawInput(rawInput);
            event.setUserEmail(email);

            event.setEventTime(null);
            event.setTriggerTime(null);

            // 新状态：PENDING（不参与 scheduler 恢复，也不会被 scheduleEvent 调度）
            event.setStatus("PENDING");

            // 回放字段照样存（对你未来“再唤醒/补时间”有用）
            if (r.getIntent() != null) event.setTriggerIntent(r.getIntent().name());
            if (r.getComplexity() != null) event.setComplexity(r.getComplexity().name());
            event.setPrepRequired(r.isPrepRequired());

            try {
                if (r.getDependencies() != null) {
                    event.setDependenciesJson(new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValueAsString(r.getDependencies()));
                }
            } catch (Exception ignore) {
                event.setDependenciesJson("[]");
            }

            event.setTriggerReason(normalized.getReason()); // fuzzy_pending:no_time_hint

            Event saved = eventRepository.save(event);
            log.info("🫴 PENDING 事件已接住: ID={}, raw={}", saved.getId(), rawInput);
            return;
        }
        // 非 PENDING：正常流程
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

    @Transactional
    public String resolvePending(String rawInput, String email) {
        log.info("🪄 resolve pending: user={}, input={}", email, rawInput);

        // 1) 用这句话做解析（它通常是“时间补充句”，比如“明天下午三点”）
        EventParseResult r = llmService.parseInput(rawInput);
        if (r == null || !Boolean.TRUE.equals(r.isValid())) {
            return "我还没听懂你希望什么时候提醒我。你可以再说具体一点，比如：明天下午3点。";
        }

        // 2) 归一化时间（如果仍然拿不到时间，就无法 resolve）
        EventTimeNormalizer.Result normalized = EventTimeNormalizer.normalize(r, rawInput, clock);
        LocalDateTime normalizedEventTime = normalized.getEventTime();
        if (normalizedEventTime == null) {
            return "我还没抓到明确的时间点。你可以试试：明天/后天/下周一 + 下午3点。";
        }

        // 3) 找到该用户最新一条 PENDING
        Event pending = eventRepository
                .findTop1ByUserEmailAndStatusOrderByCreateTimeDesc(email, "PENDING")
                .orElse(null);

        if (pending == null) {
            // 也可以选择：没有 pending 就走正常 createEvent
            return "我这边没有待定的事项需要补时间。你可以直接说：下周一提醒我做什么。";
        }

        // 4) 组装一个“用于计划”的 parseResult：以 pending 的意图/复杂度为主，用这句的时间为辅
        EventParseResult pr = new EventParseResult();
        pr.setValid(true);

        // intent / complexity 优先沿用 pending；没有再用本次解析；都没有给默认
        pr.setIntent(parseIntentOrDefault(pending.getTriggerIntent(), r));
        pr.setComplexity(parseComplexityOrDefault(pending.getComplexity(), r));
        pr.setPrepRequired(Boolean.TRUE.equals(pending.getPrepRequired()) || r.isPrepRequired());

        // dependencies：尽量从 pending 里恢复（可选）
        pr.setDependencies(readDeps(pending.getDependenciesJson()));
        // urgentMinuteLevel：用本次解析结果（因为补时间句里经常包含“5分钟后”）
        pr.setUrgentMinuteLevel(r.isUrgentMinuteLevel());

        // 5) 计算 triggerPlan（真正进入调度）
        Instant eventInstant = normalizedEventTime.atZone(ZoneId.systemDefault()).toInstant();
        TriggerPlan plan = triggerPlanService.plan(email, pr, eventInstant);

        // 6) 回填 pending 事件 → 变成 SILENT 并调度
        pending.setEventTime(normalizedEventTime);
        pending.setTriggerTime(LocalDateTime.ofInstant(plan.getTriggerTime(), ZoneId.systemDefault()));
        pending.setStatus("SILENT");

        if (plan.getBucket() != null) pending.setTriggerBucket(plan.getBucket().name());
        if (plan.getIntent() != null) pending.setTriggerIntent(plan.getIntent().name());
        if (plan.getComplexity() != null) pending.setComplexity(plan.getComplexity().name());

        String timeReason = normalized.isAssumed() ? normalized.getReason() : "llm_eventTime_used";
        String fullReason = "resolved_pending | " + timeReason + " | " + (plan.getReason() == null ? "" : plan.getReason());
        pending.setTriggerReason(fullReason);

        Event saved = eventRepository.save(pending);

        // 7) 精准挂载
        eventSchedulerService.scheduleEvent(saved);

        log.info("✅ pending resolved: id={}, eventTime={}, triggerTime={}",
                saved.getId(), saved.getEventTime(), saved.getTriggerTime());

        return "好的。我已经把「" + safeTitle(saved.getRawInput()) + "」安排好了。";
    }

    private String safeTitle(String raw) {
        if (raw == null) return "那件事";
        String s = raw.trim();
        return s.length() <= 20 ? s : s.substring(0, 20) + "...";
    }

    private com.fly.forgotyet.enums.TriggerIntent parseIntentOrDefault(String pendingIntent, EventParseResult r) {
        try {
            if (pendingIntent != null && !pendingIntent.isBlank()) {
                return com.fly.forgotyet.enums.TriggerIntent.valueOf(pendingIntent);
            }
        } catch (Exception ignore) {}
        return (r != null && r.getIntent() != null) ? r.getIntent() : com.fly.forgotyet.enums.TriggerIntent.CUSHION;
    }

    private com.fly.forgotyet.enums.Complexity parseComplexityOrDefault(String pendingComplexity, EventParseResult r) {
        try {
            if (pendingComplexity != null && !pendingComplexity.isBlank()) {
                return com.fly.forgotyet.enums.Complexity.valueOf(pendingComplexity);
            }
        } catch (Exception ignore) {}
        return (r != null && r.getComplexity() != null) ? r.getComplexity() : com.fly.forgotyet.enums.Complexity.MEDIUM;
    }

    private List<String> readDeps(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            String[] arr = com.fly.forgotyet.common.JsonUtil.toBean(json, String[].class);
            return arr == null ? List.of() : java.util.Arrays.asList(arr);
        } catch (Exception e) {
            return List.of();
        }
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

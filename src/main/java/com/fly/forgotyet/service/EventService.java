package com.fly.forgotyet.service;

import cn.hutool.core.date.LocalDateTimeUtil;
import com.fly.forgotyet.entity.Event;
import com.fly.forgotyet.entity.EventParseResult;
import com.fly.forgotyet.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final LlmService llmService;
    private final EventRepository eventRepository;
    private final EventSchedulerService eventSchedulerService;

    /**
     * 🚀 接收用户输入，保存为未来的事件 (异步处理)
     * 加入 @Async 后，该方法会在名为 task-X 的子线程中执行，前端瞬间得到响应。
     */
    @Async
    public void createEvent(String rawInput, String userEmail) {
        log.info("🧵 [异步线程] 开始处理任务: {}", rawInput);

        // 1. 先用 AI 解析 (现在是高可用的 LlmService)
        EventParseResult parseResult = llmService.parseInput(rawInput);

        // 2. 如果 AI 觉得这根本不是人话 (valid=false)，直接拒绝
        if (!parseResult.isValid()) {
            log.warn("无效的输入，AI 拒绝处理: {}", rawInput);
            return;
        }

        // 3. 构建事件实体
        Event event = new Event();
        event.setRawInput(rawInput);
        event.setUserEmail(userEmail);
        event.setStatus("SILENT");
        event.setCreateTime(LocalDateTime.now());

        // 4. 时间计算魔法
        LocalDateTime eventTime = LocalDateTimeUtil.parse(parseResult.getEventTime(), "yyyy-MM-dd HH:mm:ss");
        event.setEventTime(eventTime);
        event.setTriggerTime(eventTime);

        // 5. 落库
        eventRepository.save(event);
        log.info("新事件已存储: ID={}, 触发时间={}", event.getId(), event.getTriggerTime());

        // 6. 精准挂载
        eventSchedulerService.scheduleEvent(event);
    }
}

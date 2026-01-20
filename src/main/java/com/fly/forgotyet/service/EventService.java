package com.fly.forgotyet.service;

import cn.hutool.core.date.LocalDateTimeUtil;
import com.fly.forgotyet.entity.Event;
import com.fly.forgotyet.entity.EventParseResult;
import com.fly.forgotyet.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final LlmService llmService;
    private final EventRepository eventRepository;

    /**
     * 接收用户输入，保存为未来的事件
     */
    public boolean createEvent(String rawInput, String userEmail) {
        // 1. 先用 AI 解析
        EventParseResult parseResult = llmService.parseInput(rawInput);

        // 2. 如果 AI 觉得这根本不是人话 (valid=false)，直接拒绝
        if (!parseResult.isValid()) {
            log.warn("无效的输入，AI 拒绝处理: {}", rawInput);
            return false;
        }

        // 3. 构建事件实体
        Event event = new Event();
        event.setRawInput(rawInput);
        event.setUserEmail(userEmail); // MVP 阶段先存 Email
        event.setStatus("SILENT");     // 默认沉默
        event.setCreateTime(LocalDateTime.now());

        // 4. 时间计算魔法 (核心逻辑)
        // 事件发生时间
        LocalDateTime eventTime = LocalDateTimeUtil.parse(parseResult.getEventTime(), "yyyy-MM-dd HH:mm:ss");
        event.setEventTime(eventTime);

        // 触发时间 = 事件发生前 24 小时 (兜底策略)
        // 比如：明早 9 点的事，今早 9 点（或者前一晚）提醒
        // MVP 简单处理：减去 1 天
        event.setTriggerTime(eventTime.minusDays(1));

        // 5. 落库
        eventRepository.save(event);
        log.info("新事件已存储: ID={}, 触发时间={}", event.getId(), event.getTriggerTime());

        return true;
    }
}
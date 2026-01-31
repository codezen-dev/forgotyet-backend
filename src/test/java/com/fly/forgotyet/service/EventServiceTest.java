package com.fly.forgotyet.service;

import com.fly.forgotyet.entity.*;
import com.fly.forgotyet.enums.Complexity;
import com.fly.forgotyet.enums.TriggerBucket;
import com.fly.forgotyet.enums.TriggerIntent;
import com.fly.forgotyet.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Test
    void createEvent_shouldSaveAndSchedule_withTriggerFields() {
        // mocks
        EventRepository eventRepository = mock(EventRepository.class);
        LlmService llmService = mock(LlmService.class);
        EventSchedulerService schedulerService = mock(EventSchedulerService.class);
        TriggerPlanService triggerPlanService = mock(TriggerPlanService.class);

        // ✅ fixed clock for deterministic behavior
        ZoneId zoneId = ZoneId.systemDefault();
        Clock clock = Clock.fixed(
                LocalDateTime.of(2026, 1, 27, 10, 0, 0).atZone(zoneId).toInstant(),
                zoneId
        );

        // ✅ constructor with clock
        EventService eventService = new EventService(
                llmService, eventRepository, schedulerService, triggerPlanService, clock
        );

        String rawInput = "今晚9点提醒我开会";
        String userEmail = "a@b.com";

        // LLM parse result
        EventParseResult parseResult = new EventParseResult();
        parseResult.setValid(true);
        parseResult.setSummary("提醒我开会");
        // 建议用 T 格式，除非你已做宽松解析
        parseResult.setEventTime("2026-01-27T21:00:00");
        parseResult.setIntent(TriggerIntent.AT_TIME);
        parseResult.setComplexity(Complexity.MEDIUM);
        parseResult.setPrepRequired(false);
        parseResult.setDependencies(List.of());
        parseResult.setUrgentMinuteLevel(false);

        when(llmService.parseInput(eq(rawInput))).thenReturn(parseResult);

        // Trigger plan（策略层用 Instant）
        LocalDateTime eventLdt = LocalDateTime.of(2026, 1, 27, 21, 0, 0);
        Instant eventInstant = eventLdt.atZone(zoneId).toInstant();

        TriggerPlan plan = new TriggerPlan();
        plan.setIntent(TriggerIntent.AT_TIME);
        plan.setComplexity(Complexity.MEDIUM);
        plan.setBucket(TriggerBucket.M15);
        plan.setReason("test-reason");
        plan.setTriggerTime(eventInstant.minusSeconds(15 * 60));

        when(triggerPlanService.plan(eq(userEmail), any(EventParseResult.class), any(Instant.class)))
                .thenReturn(plan);

        // repository.save 返回入参本身（常见写法）
        when(eventRepository.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));

        // when
        eventService.createEvent(rawInput, userEmail);

        // then: capture saved event
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository, times(1)).save(captor.capture());
        Event saved = captor.getValue();

        assertNotNull(saved);
        assertEquals(rawInput, saved.getRawInput());
        assertEquals(userEmail, saved.getUserEmail());
        assertEquals("SILENT", saved.getStatus());

        // eventTime 写入正确（来自 parseResult/normalizer）
        assertEquals(eventLdt, saved.getEventTime());

        // triggerTime 写入正确（来自 plan.triggerTime 转 LocalDateTime）
        LocalDateTime expectedTriggerLdt = LocalDateTime.ofInstant(plan.getTriggerTime(), zoneId);
        assertEquals(expectedTriggerLdt, saved.getTriggerTime());

        // 回放字段（你如果没加这些字段，就把对应断言删掉）
        assertEquals("M15", saved.getTriggerBucket());
        assertEquals("AT_TIME", saved.getTriggerIntent());
        assertEquals("MEDIUM", saved.getComplexity());
        assertEquals(Boolean.FALSE, saved.getPrepRequired());
        assertNotNull(saved.getDependenciesJson());
        assertEquals("test-reason", saved.getTriggerReason());

        // scheduler 被调用
        verify(schedulerService, times(1)).scheduleEvent(any(Event.class));

        // TriggerPlanService 被调用
        verify(triggerPlanService, times(1)).plan(eq(userEmail), any(EventParseResult.class), any(Instant.class));
    }

    @Test
    void createEvent_shouldReturnDirectly_whenInvalid() {
        EventRepository eventRepository = mock(EventRepository.class);
        LlmService llmService = mock(LlmService.class);
        EventSchedulerService schedulerService = mock(EventSchedulerService.class);
        TriggerPlanService triggerPlanService = mock(TriggerPlanService.class);

        ZoneId zoneId = ZoneId.systemDefault();
        Clock clock = Clock.fixed(
                LocalDateTime.of(2026, 1, 27, 10, 0, 0).atZone(zoneId).toInstant(),
                zoneId
        );

        EventService eventService = new EventService(
                llmService, eventRepository, schedulerService, triggerPlanService, clock
        );

        String rawInput = "这不是提醒";
        String userEmail = "a@b.com";

        EventParseResult parseResult = new EventParseResult();
        parseResult.setValid(false);

        when(llmService.parseInput(eq(rawInput))).thenReturn(parseResult);

        eventService.createEvent(rawInput, userEmail);

        verify(eventRepository, never()).save(any());
        verify(schedulerService, never()).scheduleEvent(any());
        verify(triggerPlanService, never()).plan(anyString(), any(), any());
    }
}


package com.fly.forgotyet.controller;

import com.fly.forgotyet.common.R;
import com.fly.forgotyet.service.EventService;
import com.sun.jdi.request.EventRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/fy-api/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping("/add")
    public R<String> addEvent(@RequestBody EventRequest request) {
        // 简单的参数校验
        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            return R.error("内容不能为空");
        }

        // 调用 Service
        eventService.createEvent(request.getContent(), "lizhenweijie@gmail.com");

        return R.success("已记住。");

    }

    // 内部 DTO
    @Data
    public static class EventRequest {
        private String content;
    }
}

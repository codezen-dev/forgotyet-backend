package com.fly.forgotyet.controller;

import com.fly.forgotyet.common.R;
import com.fly.forgotyet.service.EventService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
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
        boolean success = eventService.createEvent(request.getContent(), "user@default.com"); // MVP 暂时写死用户

        if (success) {
            return R.success("已记住。"); // 极其克制的返回
        } else {
            return R.error("没太听懂，这好像不是一个未来的计划？");
        }
    }

    // 内部 DTO
    @Data
    public static class EventRequest {
        private String content;
    }
}
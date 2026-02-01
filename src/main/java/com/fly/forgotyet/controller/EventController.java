package com.fly.forgotyet.controller;

import com.fly.forgotyet.common.R;
import com.fly.forgotyet.entity.Event;
import com.fly.forgotyet.enums.TriggerFeedback;
import com.fly.forgotyet.service.EventService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.crypto.SecretKey;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/fy-api/api/event")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    // 和 AuthService 里保持一致的密钥
    private static final String SECRET_STR = "ForgotYet2026SuperSecretKeyForJWTAuth!!";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET_STR.getBytes());

    @PostMapping("/add")
    public R<String> addEvent(@RequestBody EventRequest request, HttpServletRequest httpRequest) {
        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            return R.error("内容不能为空");
        }

        String email = extractEmailFromToken(httpRequest);
        eventService.createEvent(request.getContent(), email);

        return R.success("已收录。不用再挂念它，去享受生活吧。");
    }

    /**
     * V1：最近事件列表
     */
    @GetMapping("/list")
    public R<List<EventListItem>> list(@RequestParam(defaultValue = "10") int limit,
                                       HttpServletRequest httpRequest) {
        String email = extractEmailFromToken(httpRequest);
        List<Event> events = eventService.listRecentEvents(email, limit);

        List<EventListItem> items = events.stream().map(EventListItem::from).collect(Collectors.toList());
        return R.success(items);
    }

    /**
     * V1：提交反馈（早/好/晚）
     */
    @PostMapping("/feedback")
    public R<String> feedback(@RequestBody FeedbackRequest request,
                              HttpServletRequest httpRequest) {
        if (request.getEventId() == null) {
            return R.error("eventId 不能为空");
        }
        if (request.getFeedback() == null) {
            return R.error("feedback 不能为空");
        }

        String email = extractEmailFromToken(httpRequest);
        eventService.submitFeedback(email, request.getEventId(), request.getFeedback());
        return R.success("ok");
    }

    @PostMapping("/{id}/cancel")
    public R<String> cancel(@PathVariable("id") Long id,
                            HttpServletRequest httpRequest) {
        String email = extractEmailFromToken(httpRequest);
        eventService.cancelEvent(id, email);
        return R.success("已取消");
    }


    // 解析 Token 的辅助方法
    private String extractEmailFromToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("未登录或 Token 失效");
        }
        String token = authHeader.substring(7);

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(KEY)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // ✅ 兼容短信登录：优先用 claim.email；没有再退回 subject
            Object email = claims.get("email");
            if (email != null && !String.valueOf(email).isBlank()) {
                return String.valueOf(email);
            }
            return claims.getSubject();
        } catch (Exception e) {
            throw new RuntimeException("Token 解析失败，请重新登录");
        }
    }


    @Data
    public static class EventRequest {
        private String content;
    }

    @Data
    public static class FeedbackRequest {
        private Long eventId;
        private TriggerFeedback feedback;
    }

    @Data
    public static class EventListItem {
        private Long id;
        private String rawInput;
        private LocalDateTime eventTime;
        private LocalDateTime triggerTime;
        private String status;
        private TriggerFeedback feedback;
        private String triggerReason;

        public static EventListItem from(Event e) {
            EventListItem i = new EventListItem();
            i.setId(e.getId());
            i.setRawInput(e.getRawInput());
            i.setEventTime(e.getEventTime());
            i.setTriggerTime(e.getTriggerTime());
            i.setStatus(e.getStatus());
            i.setFeedback(e.getFeedback());
            i.setTriggerReason(e.getTriggerReason());
            return i;
        }
    }
}

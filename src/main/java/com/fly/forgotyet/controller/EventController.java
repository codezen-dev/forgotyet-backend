package com.fly.forgotyet.controller;

import com.fly.forgotyet.common.R;
import com.fly.forgotyet.service.EventService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.crypto.SecretKey;
import javax.servlet.http.HttpServletRequest;

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
        // 1. 参数校验
        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            return R.error("内容不能为空");
        }

        // 2. 从 Request Header 中提取 JWT Token 并解析出真实邮箱
        String email = extractEmailFromToken(httpRequest);

        // 3. 调用 Service (传入真实的邮箱)
        eventService.createEvent(request.getContent(), email);

        return R.success("已收录。不用再挂念它，去享受生活吧。");
    }

    // 解析 Token 的辅助方法
    private String extractEmailFromToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new RuntimeException("未登录或 Token 失效");
        }
        String token = authHeader.substring(7); // 去掉 "Bearer "

        try {
            // 解析 JWT 获取 subject (即 email)
            Claims claims = Jwts.parser()
                    .verifyWith(KEY)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (Exception e) {
            throw new RuntimeException("Token 解析失败，请重新登录");
        }
    }

    // 内部 DTO
    @Data
    public static class EventRequest {
        private String content; // 这里的 content 刚好对应前端刚改的 content
    }
}

package com.fly.forgotyet.service;

import cn.hutool.cache.CacheUtil;
import cn.hutool.cache.impl.TimedCache;
import cn.hutool.core.util.RandomUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final EmailService emailService;

    // JWT 密钥 (正式环境请移入 yml)
    private static final String SECRET_STR = "ForgotYet2026SuperSecretKeyForJWTAuth!!";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET_STR.getBytes());

    // 内存缓存：存放邮箱验证码，5分钟过期 (零运维方案)
    private static final TimedCache<String, String> codeCache = CacheUtil.newTimedCache(5 * 60 * 1000);

    /**
     * 发送邮箱验证码
     */
    public void sendCode(String email) {
        String code = RandomUtil.randomNumbers(4);
        codeCache.put(email, code);

        String content = "【ForgotYet】你的登录验证码是：" + code + "，有效期5分钟。愿你拥有平静的一天。";
        emailService.sendSimpleEmail(email, "登录验证码", content);
        log.info("📧 验证码已发送至: {}", email);
    }

    /**
     * 邮箱验证码登录：subject=email
     */
    public String login(String email, String code) {
        String cachedCode = codeCache.get(email);
        if (cachedCode == null || !cachedCode.equals(code)) {
            throw new RuntimeException("验证码错误或已过期");
        }
        codeCache.remove(email);
        return issueJwt(email, email);
    }

    /**
     * 统一签 JWT
     * - subject: email 登录时就是 email；短信登录时可以是 phone
     * - email: 一定尽量带上，便于 EventController 拿到 email 做提醒
     */
    public String issueJwt(String subject, String email) {
        var builder = Jwts.builder()
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 30))
                .signWith(KEY);

        if (email != null && !email.isBlank()) {
            builder.claim("email", email);
        }
        return builder.compact();
    }

    public static SecretKey jwtKey() {
        return KEY;
    }
}

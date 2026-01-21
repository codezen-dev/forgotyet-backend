package com.fly.forgotyet.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    // 从配置文件读取发件人（必须和配置的 username 一致）
    @Value("${spring.mail.username}")
    private String fromEmail;

    /**
     * 发送纯文本邮件 (异步执行，不阻塞主线程)
     */
    @Async
    public void sendSimpleEmail(String to, String subject, String content) {
        try {
            log.info(">>> 正在尝试发送邮件给: {}", to);

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(content);

            mailSender.send(message);

            log.info(">>> 邮件发送成功!");
        } catch (Exception e) {
            log.error("邮件发送失败: ", e);
            // 这里未来可以加重试机制
        }
    }
}
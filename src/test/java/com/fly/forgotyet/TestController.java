package com.fly.forgotyet;

import com.fly.forgotyet.service.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.GetMapping;

@SpringBootTest
public class TestController {

    @Autowired
    private EmailService emailService;

    @Test
    void testEmail() {
        emailService.sendSimpleEmail("lizhenweijie@gmail.com", "ForgotYet 测试", "你好，这是来自未来的问候。");
    }

}

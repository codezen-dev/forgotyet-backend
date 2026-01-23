package com.fly.forgotyet.controller;

import com.fly.forgotyet.common.R;
import com.fly.forgotyet.service.AuthService;
import com.fly.forgotyet.service.VoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/fy-api/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/send-code")
    public R<String> sendCode(@RequestParam String email) {
        authService.sendCode(email);
        return R.success("验证码已发送");
    }

    @PostMapping("/login")
    public R<String> login(@RequestParam String email, @RequestParam String code) {
        return R.success(authService.login(email, code));
    }
}


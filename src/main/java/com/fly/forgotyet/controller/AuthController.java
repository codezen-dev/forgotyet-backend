package com.fly.forgotyet.controller;

import com.fly.forgotyet.common.R;
import com.fly.forgotyet.service.AliyunPnvsSmsService;
import com.fly.forgotyet.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/fy-api/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final AliyunPnvsSmsService aliyunPnvsSmsService;

    // ====== 邮箱登录（原有） ======

    @PostMapping("/send-code")
    public R<String> sendCode(@RequestParam String email) {
        authService.sendCode(email);
        return R.success("验证码已发送");
    }

    @PostMapping("/login")
    public R<String> login(@RequestParam String email, @RequestParam String code) {
        return R.success(authService.login(email, code));
    }

    // ====== 短信登录（新增） ======
    // 说明：为了不破坏你“邮件提醒”链路，短信登录阶段先要求传 email 作为提醒邮箱。
    // 后续你做 PWA Push 后，可以把 email 变成可选。

    @PostMapping("/sms/send-code")
    public R<String> sendSmsCode(@RequestParam String phone) {
        if (phone == null || phone.trim().isEmpty()) return R.error("手机号不能为空");
        aliyunPnvsSmsService.sendCode(phone.trim());
        return R.success("ok");
    }

    @PostMapping("/sms/login")
    public R<String> smsLogin(@RequestParam String phone,
                              @RequestParam String code,
                              @RequestParam String email) {
        if (phone == null || phone.trim().isEmpty()) return R.error("手机号不能为空");
        if (code == null || code.trim().isEmpty()) return R.error("验证码不能为空");
        if (email == null || email.trim().isEmpty()) return R.error("需要绑定邮箱（当前提醒通过邮件发送）");

        boolean ok = aliyunPnvsSmsService.verifyCode(phone.trim(), code.trim());
        if (!ok) return R.error("验证码错误");

        // ✅ subject 用 phone；同时把 email 放进 claim，EventController 会优先取 claim.email
        String token = authService.issueJwt(phone.trim(), email.trim());
        return R.success(token);
    }
}

package com.fly.forgotyet.controller;

import com.fly.forgotyet.common.R;
import com.fly.forgotyet.service.ConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/fy-api/admin/config")
@RequiredArgsConstructor
public class AdminConfigController {

    private final ConfigService configService;

    /**
     * 手动刷新缓存
     */
    @PostMapping("/refresh")
    public R<String> refresh() {
        configService.refreshCache();
        return R.success("Prompt配置已热更新");
    }
}

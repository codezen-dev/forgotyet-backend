package com.fly.forgotyet.controller;

import cn.hutool.cache.CacheUtil;
import cn.hutool.cache.impl.TimedCache;
import com.fly.forgotyet.common.R;
import com.fly.forgotyet.service.VoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;

@Slf4j
@RestController
@RequestMapping("/api/voice")
@RequiredArgsConstructor
public class VoiceController {

    private final VoiceService voiceService;

    // 🚀 内存级防刷锁：存放每个 IP 的请求次数，1 分钟自动过期清理
    // 容量 1000，过期时间 60 秒
    private static final TimedCache<String, Integer> IP_RATE_LIMITER = CacheUtil.newTimedCache(60 * 1000);

    /**
     * 接收前端语音文件 -> 转成文字返回
     */
    @PostMapping("/transcribe")
    public R<String> transcribe(@RequestParam("file") MultipartFile file, HttpServletRequest request) {

        // 1. 获取用户真实 IP (考虑了 Nginx 代理的情况)
        String ip = getClientIp(request);

        // 2. 检查频率：每分钟最多 5 次
        Integer count = IP_RATE_LIMITER.get(ip, false);
        if (count != null && count >= 5) {
            log.warn("⚠️ 触发防刷风控，IP: {} 请求太频繁", ip);
            return R.error("您说得太快啦，请休息 1 分钟再试~");
        }

        // 3. 记录次数
        IP_RATE_LIMITER.put(ip, count == null ? 1 : count + 1);

        // 4. 空文件拦截
        if (file.isEmpty()) {
            return R.error("音频文件不能为空");
        }

        // 5. 调用云端转换
        try {
            String text = voiceService.transcribeAudio(file);
            return R.success(text);
        } catch (Exception e) {
            log.error("语音转换异常", e);
            return R.error("语音识别暂时忙碌，请直接打字输入");
        }
    }

    // 获取真实IP的工具方法
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip.contains(",") ? ip.split(",")[0] : ip;
    }
}

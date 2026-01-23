package com.fly.forgotyet.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

@Slf4j
@Service
public class VoiceService {

    @Value("${voice.api-url}")
    private String apiUrl;

    @Value("${voice.api-key}")
    private String apiKey;

    @Value("${voice.model}")
    private String modelName;

    /**
     * 将音频文件转发给云端 Whisper 进行转录
     */
    public String transcribeAudio(MultipartFile audioFile) throws Exception {
        // 1. 生成临时文件 (Hutool 发送 form-data 需要 File 对象)
        File tempFile = createTempFile(audioFile);
        log.info("🎙️ 接收到前端语音，大小: {} KB，准备调用云端 Whisper...", tempFile.length() / 1024);

        try {
            // 2. 调用阿里云 Whisper API (标准 OpenAI 协议)
            HttpResponse response = HttpRequest.post(apiUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .form("model", modelName)
                    .form("file", tempFile)
                    .form("language", "zh") // 指定中文，识别更快
                    .timeout(15000) // 语音识别给长一点的超时时间：15秒
                    .execute();

            if (!response.isOk()) {
                log.error("❌ 语音识别 API 调用失败: {}", response.body());
                throw new RuntimeException("语音转文字服务暂时不可用");
            }

            // 3. 提取文字
            String transcribedText = JSONUtil.parseObj(response.body()).getStr("text");
            log.info("✅ 语音识别成功: {}", transcribedText);

            return transcribedText;

        } finally {
            // 4. 清理临时文件，防止把服务器硬盘撑爆
            if (tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    private File createTempFile(MultipartFile file) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String suffix = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf("."))
                : ".webm"; // 前端录音通常是 webm 或 mp3

        File tempFile = File.createTempFile("voice_", suffix);
        file.transferTo(tempFile);
        return tempFile;
    }
}

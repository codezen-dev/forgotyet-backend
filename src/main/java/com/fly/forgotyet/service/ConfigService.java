package com.fly.forgotyet.service;

import com.fly.forgotyet.entity.AppConfig;
import com.fly.forgotyet.repository.AppConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final AppConfigRepository appConfigRepository;
    // 内存缓存
    private final Map<String, String> configCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refreshCache();
    }

    public void refreshCache() {
        log.info(">>> 开始刷新系统配置缓存...");
        appConfigRepository.findAll().forEach(config -> {
            configCache.put(config.getConfigKey(), config.getConfigValue());
        });
        log.info(">>> 配置缓存刷新完成，加载配置项: {}", configCache.size());
    }

    public String getPrompt(String key, String defaultValue) {
        return configCache.getOrDefault(key, defaultValue);
    }
}
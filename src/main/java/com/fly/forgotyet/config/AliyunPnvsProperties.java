package com.fly.forgotyet.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "aliyun.pnvs")
public class AliyunPnvsProperties {
    private String accessKeyId;
    private String accessKeySecret;
    private String endpoint = "dypnsapi.aliyuncs.com";
    private String regionId = "cn-hangzhou";

    private String templateCode;
    private String schemeName;
    private String countryCode = "86";
    private String templateMin = "5";
}

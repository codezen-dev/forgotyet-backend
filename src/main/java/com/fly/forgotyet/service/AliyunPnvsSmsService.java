package com.fly.forgotyet.service;

import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.dypnsapi20170525.AsyncClient;
import com.aliyun.sdk.service.dypnsapi20170525.models.CheckSmsVerifyCodeRequest;
import com.aliyun.sdk.service.dypnsapi20170525.models.CheckSmsVerifyCodeResponse;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.fly.forgotyet.common.JsonUtil;
import com.fly.forgotyet.config.AliyunPnvsProperties;
import darabonba.core.client.ClientOverrideConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class AliyunPnvsSmsService {

    private final AliyunPnvsProperties props;

    private volatile AsyncClient client;

    private AsyncClient getClient() {
        if (client != null) return client;
        synchronized (this) {
            if (client != null) return client;

            // 1) AK/SK
            Credential credential = Credential.builder()
                    .accessKeyId(props.getAccessKeyId())
                    .accessKeySecret(props.getAccessKeySecret())
                    .build();

            StaticCredentialProvider provider = StaticCredentialProvider.create(credential);

            // 2) Endpoint
            ClientOverrideConfiguration override = ClientOverrideConfiguration.create()
                    .setEndpointOverride(props.getEndpoint()); // 例如 dypnsapi.aliyuncs.com

            // 3) Build AsyncClient
            client = AsyncClient.builder()
                    .credentialsProvider(provider)
                    .overrideConfiguration(override)
                    .build();

            return client;
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            if (client != null) client.close();
        } catch (Exception ignore) {
        }
    }

    /** 发送短信验证码（阿里云生成 code：TemplateParam.code=##code##） */
    public void sendCode(String phone) {
        try {
            // templateParam：你模板里如果写了 ${min} 就传；不需要也没关系
            Map<String, String> templateParam = new HashMap<>();
            templateParam.put("code", "##code##"); // 让阿里云生成验证码
            templateParam.put("min", props.getTemplateMin());

            SendSmsVerifyCodeRequest.Builder builder = SendSmsVerifyCodeRequest.builder()
                    .phoneNumber(phone)
                    .countryCode(props.getCountryCode())
                    .templateCode(props.getTemplateCode())
                    .templateParam(JsonUtil.toJson(templateParam))
                    .signName("速通互联验证码")
                    .codeType(1L)     // 1=数字
                    .codeLength(6L);  // 6位

            if (props.getSchemeName() != null && !props.getSchemeName().isBlank()) {
                builder.schemeName(props.getSchemeName());
            }

            CompletableFuture<SendSmsVerifyCodeResponse> future = getClient().sendSmsVerifyCode(builder.build());
            SendSmsVerifyCodeResponse resp = future.get();

            // SDK 2.0.0 的返回结构：resp.getBody() 里有 code/message/success 等
            if (resp == null || resp.getBody() == null) {
                throw new RuntimeException("null_resp");
            }

            String code = resp.getBody().getCode();
            String msg = resp.getBody().getMessage();

            if (!"OK".equalsIgnoreCase(code)) {
                throw new RuntimeException("send sms failed: " + msg + " (code=" + code + ")");
            }

        } catch (Exception e) {
            log.error("sendCode failed phone={}", phone, e);
            throw new RuntimeException("短信发送失败");
        }
    }

    /** 校验验证码：VerifyResult=PASS 才算成功 */
    public boolean verifyCode(String phone, String verifyCode) {
        try {
            CheckSmsVerifyCodeRequest.Builder builder = CheckSmsVerifyCodeRequest.builder()
                    .phoneNumber(phone)
                    .countryCode(props.getCountryCode())
                    .verifyCode(verifyCode);

            if (props.getSchemeName() != null && !props.getSchemeName().isBlank()) {
                builder.schemeName(props.getSchemeName());
            }

            CompletableFuture<CheckSmsVerifyCodeResponse> future = getClient().checkSmsVerifyCode(builder.build());
            CheckSmsVerifyCodeResponse resp = future.get();

            if (resp == null || resp.getBody() == null) return false;
            if (!Boolean.TRUE.equals(resp.getBody().getSuccess())) return false;

            var model = resp.getBody().getModel();
            if (model == null) return false;

            return "PASS".equalsIgnoreCase(model.getVerifyResult());


        } catch (Exception e) {
            log.error("verifyCode failed phone={}", phone, e);
            return false;
        }
    }
}

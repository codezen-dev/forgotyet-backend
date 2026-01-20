package com.fly.forgotyet;

import com.fly.forgotyet.entity.EventParseResult;
import com.fly.forgotyet.service.LlmService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class LlmTest {
    @Autowired
    private LlmService llmService;

    @Test
    void testParse() {
        EventParseResult result = llmService.parseInput("下周三上午去医院复查");
        System.out.println("解析结果: " + result);
    }
}

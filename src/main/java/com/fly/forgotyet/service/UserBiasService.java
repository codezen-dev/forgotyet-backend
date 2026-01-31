package com.fly.forgotyet.service;

import com.fly.forgotyet.entity.Event;
import com.fly.forgotyet.enums.TriggerFeedback;
import com.fly.forgotyet.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserBiasService {

    private final EventRepository eventRepository;

    /**
     * C3-2: 分钟级 bucket (M0/M15...) 不参与学习
     * 返回偏好步数：+1 / 0 / -1（你也可以保持你原来的阈值策略）
     */
    public int computeBiasSteps(String userEmail) {
        // 只看最近 N 条已送达事件
        int N = 12;

        List<Event> events = eventRepository
                .findByUserEmailAndStatusOrderByCreateTimeDesc(
                        userEmail,
                        "DELIVERED",
                        PageRequest.of(0, N, Sort.by(Sort.Direction.DESC, "createTime"))
                )
                .getContent();

        if (events == null || events.isEmpty()) return 0;

        // ✅ C3-2：过滤分钟级 bucket：任何以 "M" 开头的都不参与学习（M0/M15）
        List<Event> filtered = events.stream()
                .filter(e -> {
                    String b = e.getTriggerBucket();
                    return b == null || b.isBlank() || !b.startsWith("M");
                })
                .toList();

        if (filtered.isEmpty()) return 0;

        int early = 0;
        int late = 0;

        for (Event e : filtered) {
            TriggerFeedback f = e.getFeedback(); // ✅ 枚举
            if (f == null) continue;
            if (f == TriggerFeedback.EARLY) early++;
            else if (f == TriggerFeedback.LATE) late++;
        }

        // 净倾向：early 多 => 需要更晚；late 多 => 需要更早
        int score = early - late;

        // 每 2 次倾向移动 1 档，减少抖动
        int steps = (int) Math.round(score / 2.0);

        // 安全上限：最多 ±2 档
        if (steps > 2) steps = 2;
        if (steps < -2) steps = -2;

        return steps;
    }

}

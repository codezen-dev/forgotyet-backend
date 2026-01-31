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
     * 计算用户偏好偏移（以 bucket 档位为单位）
     * 正值：整体更晚（减少提前量）
     * 负值：整体更早（增加提前量）
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

        int early = 0;
        int late = 0;

        for (Event e : events) {
            TriggerFeedback f = e.getFeedback();
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

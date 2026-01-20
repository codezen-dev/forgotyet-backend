package com.fly.forgotyet.entity;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "t_event")
@EntityListeners(AuditingEntityListener.class)
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 用户的原话 (核心资产)
    // 使用 TEXT 类型，防止用户输入太长报错
    @Column(columnDefinition = "TEXT", nullable = false)
    private String rawInput;

    // 事件发生的实际时间 (LLM 解析出来的)
    @Column(nullable = false)
    private LocalDateTime eventTime;

    // 触发通知的时间 (计算出来的，通常是 eventTime - 24h)
    @Column(nullable = false)
    private LocalDateTime triggerTime;

    // 状态: SILENT (默认), DELIVERED (已发送), CANCELLED (取消)
    @Column(nullable = false)
    private String status;

    // 用户邮箱 (暂时代替用户ID)
    @Column(nullable = false)
    private String userEmail;

    // 创建时间
    @CreatedDate
    private LocalDateTime createTime;
}
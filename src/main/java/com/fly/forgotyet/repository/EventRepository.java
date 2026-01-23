package com.fly.forgotyet.repository;

import com.fly.forgotyet.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {

    // 核心查询：找那些 "状态是沉默" 且 "触发时间已到" 的事件
    List<Event> findByStatusAndTriggerTimeBefore(String status, LocalDateTime now);

    List<Event> findByStatusAndTriggerTimeAfter(String status, LocalDateTime now);
}
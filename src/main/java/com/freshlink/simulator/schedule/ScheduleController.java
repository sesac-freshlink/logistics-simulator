package com.freshlink.simulator.schedule;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** GET /simulator/schedule: 오늘 입고 여부, 주문 수, 다음 실행 시각. 스케줄이 꺼져 있으면 enabled=false만 */
@RestController
public class ScheduleController {

    private final ObjectProvider<DemoScheduler> scheduler;

    public ScheduleController(ObjectProvider<DemoScheduler> scheduler) {
        this.scheduler = scheduler;
    }

    @GetMapping("/simulator/schedule")
    public Object status() {
        DemoScheduler s = scheduler.getIfAvailable();
        return s == null ? Map.of("enabled", false) : s.status();
    }
}

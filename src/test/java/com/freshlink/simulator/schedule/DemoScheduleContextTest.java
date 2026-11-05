package com.freshlink.simulator.schedule;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 스케줄을 켜면 빈이 이어지고 설정값이 읽히는지 (켜질 때 입고·주문은 끈다) */
@SpringBootTest(properties = {"simulator.schedule.enabled=true", "simulator.schedule.inbound-on-startup=false",
        "simulator.schedule.customers=a:1,2;b:3", "simulator.schedule.tick=1h"})
class DemoScheduleContextTest {

    @Autowired
    DemoScheduler scheduler;

    @Test
    void 빈이_뜨고_고객_단골_점포가_대상_점포가_된다() {
        DemoScheduler.Status status = scheduler.status();
        assertThat(status.enabled()).isTrue();
        assertThat(status.ordersEnabled()).isFalse();
        assertThat(status.customers()).isEqualTo(2);
        assertThat(status.stores()).isEqualTo(3);
    }
}

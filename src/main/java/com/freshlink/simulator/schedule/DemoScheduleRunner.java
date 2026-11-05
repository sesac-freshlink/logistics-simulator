package com.freshlink.simulator.schedule;

import com.freshlink.simulator.config.ScheduleProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/** 스프링 예약: 아침 입고(cron, 한국 시각), 켜질 때 입고 한 번, tick마다 주문·픽업 처리 */
public class DemoScheduleRunner {

    private final DemoScheduler scheduler;
    private final ScheduleProperties props;

    public DemoScheduleRunner(DemoScheduler scheduler, ScheduleProperties props) {
        this.scheduler = scheduler;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (props.inboundOnStartup()) {
            scheduler.inboundToday();
        }
    }

    @Scheduled(cron = "${simulator.schedule.inbound-cron:0 20 9 * * *}", zone = "Asia/Seoul")
    public void morningInbound() {
        scheduler.inboundToday();
    }

    @Scheduled(fixedDelayString = "${simulator.schedule.tick:20s}", initialDelayString = "${simulator.schedule.tick:20s}")
    public void tick() {
        scheduler.tick();
    }
}

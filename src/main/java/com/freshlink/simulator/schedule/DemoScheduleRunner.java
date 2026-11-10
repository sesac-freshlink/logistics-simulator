package com.freshlink.simulator.schedule;

import com.freshlink.simulator.config.ScheduleProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 스프링 예약: 아침 폐기·입고(cron, 한국 시각), 켜질 때 입고 한 번, tick마다 주문·픽업 처리.
 * 켜질 때 입고는 오늘 cron 시각이 지난 뒤에만 한다. prod는 08:40에 클러스터를 만들어 09:20 cron보다 먼저 뜨는데,
 * 그때 입고해 버리면 09:20 폐기가 방금 넣은 어제 마감 시연 lot까지 지운다(11/10)
 */
public class DemoScheduleRunner {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DemoScheduler scheduler;
    private final ScheduleProperties props;

    private final Clock clock;

    public DemoScheduleRunner(DemoScheduler scheduler, ScheduleProperties props, Clock clock) {
        this.scheduler = scheduler;
        this.props = props;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (props.inboundOnStartup() && cronPassedToday(props.inboundCron(), ZonedDateTime.now(clock.withZone(KST)))) {
            scheduler.inboundToday(false);
        }
    }

    @Scheduled(cron = "${simulator.schedule.inbound-cron:0 20 9 * * *}", zone = "Asia/Seoul")
    public void morningInbound() {
        scheduler.inboundToday(true);
    }

    /** 오늘 0시 뒤 첫 cron 시각이 오늘이고 이미 지났는지 (오늘 cron이 없는 요일이면 false) */
    static boolean cronPassedToday(String cron, ZonedDateTime now) {
        ZonedDateTime first = CronExpression.parse(cron).next(now.toLocalDate().atStartOfDay(now.getZone()).minusSeconds(1));
        return first != null && first.toLocalDate().equals(now.toLocalDate()) && !first.isAfter(now);
    }

    @Scheduled(fixedDelayString = "${simulator.schedule.tick:20s}", initialDelayString = "${simulator.schedule.tick:20s}")
    public void tick() {
        scheduler.tick();
    }
}

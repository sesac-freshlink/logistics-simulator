package com.freshlink.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * {@code simulator.schedule.*}: prod 데모용 매일 시나리오 (.github docs/할일/3차멘토링-사람별-할일.md 1-2).
 * 아침 입고 → 낮 동안 가상 고객 주문 → 결제 완료 → 픽업 준비 → 픽업. 대형 부하 테스트가 아니다(30곳 합쳐 하루 100–200건).
 *
 * @param enabled          스케줄 전체 켜기. 끄면 손 입고(POST /simulator/shipments)만 된다
 * @param inboundCron      아침 입고 시각 (Spring cron, 한국 시각). 다음 입고는 같은 날 shipmentId가 같아 inventory가 한 번만 받는다
 * @param inboundOnStartup 켜질 때 오늘 입고를 한 번 보낸다 (클러스터가 09:20 뒤에 떠도 그날 재고가 생기게)
 * @param stores           대상 점포 ID (infra 시드 점포). 비면 고객 단골 점포 전부
 * @param customers        가상 고객 "sub:점포,점포;sub:점포" (Secrets Manager → External Secrets → SIMULATOR_CUSTOMERS)
 * @param tick             주문·픽업 처리를 확인하는 간격
 * @param disposeExpired   아침 입고(cron) 직전에 점포마다 점주 대신 "경과 lot 한 번에 폐기"를 부른다.
 *                         dev는 팀원이 폐기를 시험하므로 끄고 prod만 켠다 (11/10)
 * @param launch           시연용 신상품 첫 입고 기간 (비면 신상품을 입고하지 않는다)
 */
@ConfigurationProperties("simulator.schedule")
public record ScheduleProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("0 20 9 * * *") String inboundCron,
        @DefaultValue("true") boolean inboundOnStartup,
        @DefaultValue List<Long> stores,
        @DefaultValue("") String customers,
        @DefaultValue("20s") Duration tick,
        @DefaultValue("false") boolean disposeExpired,
        @DefaultValue Launch launch,
        @DefaultValue Orders orders,
        @DefaultValue Pickup pickup,
        @DefaultValue Services services
) {

    /**
     * @param enabled          주문 시나리오 켜기 (dev는 기본 false: 팀원 시험 데이터와 섞이지 않게)
     * @param perStoreMin      점포당 하루 주문 수 최소
     * @param perStoreMax      점포당 하루 주문 수 최대
     * @param windowStart      주문 시작 시각. 점심 11:30–13:30, 저녁 17:30–19:30은 두 배로 몰린다
     * @param windowEnd        주문 끝 시각 (prod 21:00 끄기 전)
     * @param unpaidRate       결제하지 않고 두는 비율 (10분 뒤 EXPIRED)
     * @param cancelRate       결제 뒤 고객이 취소·환불하는 비율 (order SIM- 환불이 된 환경에서만 켠다)
     * @param paymentWait      주문 뒤 PAYMENT_WAITING이 될 때까지 기다리는 최대 시간
     * @param clearanceRate    마감세일(24시간 안 lot, 06–23시)로 사는 주문 비율. 마감세일 상품이 없으면 기본 주문으로 산다
     */
    public record Orders(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("3") int perStoreMin,
            @DefaultValue("6") int perStoreMax,
            @DefaultValue("10:00") LocalTime windowStart,
            @DefaultValue("20:00") LocalTime windowEnd,
            @DefaultValue("0.05") double unpaidRate,
            @DefaultValue("0") double cancelRate,
            @DefaultValue("30s") Duration paymentWait,
            @DefaultValue("0") double clearanceRate
    ) {
    }

    /**
     * 시연용 신상품(Catalog.LAUNCH) 첫 입고. 점포마다 perStore개를 골라 from–to 사이 날짜에 나눠 처음 입고하고, 그 뒤로는 다른 상품처럼 채운다.
     * 고객 화면 신상품 배지는 점포 첫 입고 뒤 7일이라, 시연일 전 7일 안(예: 시연 11/20 → 11/14–11/19)으로 잡는다
     *
     * @param from     첫 입고 시작 날짜 (비면 끈다)
     * @param to       첫 입고 끝 날짜
     * @param perStore 점포마다 신상품 수
     */
    public record Launch(
            LocalDate from,
            LocalDate to,
            @DefaultValue("3") int perStore
    ) {

        public boolean enabled() {
            return from != null && to != null && !to.isBefore(from) && perStore > 0;
        }
    }

    /** 결제 뒤 픽업 준비, 준비 뒤 픽업 확인까지 걸리는 시간(사이 값을 고른다)과 픽업하지 않는 비율 */
    public record Pickup(
            @DefaultValue("5m") Duration readyAfterMin,
            @DefaultValue("20m") Duration readyAfterMax,
            @DefaultValue("10m") Duration doneAfterMin,
            @DefaultValue("40m") Duration doneAfterMax,
            @DefaultValue("0.1") double noShowRate
    ) {
    }

    /** 클러스터 안 Service 주소. gateway를 거치지 않고 X-User-* 헤더를 시뮬레이터가 넣는다 */
    public record Services(
            @DefaultValue("http://order-service:8080") String order,
            @DefaultValue("http://inventory-service:8080") String inventory,
            @DefaultValue("http://member-service:8080") String member
    ) {
    }
}

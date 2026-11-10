package com.freshlink.simulator.schedule;

import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.simulator.config.ScheduleProperties;
import com.freshlink.simulator.messaging.InboundPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 실제 order·inventory·member 대신 가짜 서버(MockRestServiceServer)로 하루 흐름을 확인한다. 큐는 목 */
class DemoSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String SUB = "11111111-1111-1111-1111-111111111111";

    InboundPublisher publisher = mock(InboundPublisher.class);
    MockRestServiceServer server;
    ShopClient shop;
    MutableClock clock;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        shop = new ShopClient(builder, new ScheduleProperties.Services("http://order", "http://inventory", "http://member"));
    }

    /** 하루 1건만 계획되게: 점포 4 하나, 단골 1명, 점포당 1건, 결제 안 함·취소·노쇼 없음 */
    private ScheduleProperties props(boolean ordersEnabled, double noShow) {
        return props(ordersEnabled, noShow, false, 0);
    }

    private ScheduleProperties props(boolean ordersEnabled, double noShow, boolean dispose, double clearanceRate) {
        return new ScheduleProperties(true, "0 20 9 * * *", false, List.of(4L), SUB + ":4", Duration.ofSeconds(20),
                dispose, new ScheduleProperties.Launch(null, null, 3),
                new ScheduleProperties.Orders(ordersEnabled, 1, 1, LocalTime.of(10, 0), LocalTime.of(20, 0), 0, 0,
                        Duration.ofSeconds(30), clearanceRate),
                new ScheduleProperties.Pickup(Duration.ofMinutes(5), Duration.ofMinutes(20), Duration.ofMinutes(10),
                        Duration.ofMinutes(40), noShow),
                new ScheduleProperties.Services("http://order", "http://inventory", "http://member"));
    }

    private DemoScheduler scheduler(ScheduleProperties props) {
        return new DemoScheduler(props, publisher, shop, clock, d -> clock.advance(d));
    }

    @Test
    void 아침_입고는_점포마다_고정_shipmentId로_보낸다() {
        clock = new MutableClock(ZonedDateTime.of(2026, 11, 5, 9, 20, 0, 0, KST));
        DemoScheduler s = scheduler(props(false, 0));
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2), requestTo("http://inventory/stores/4/products"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        s.inboundToday();
        s.inboundToday();
        server.verify();

        ArgumentCaptor<InboundMessage> sent = ArgumentCaptor.forClass(InboundMessage.class);
        verify(publisher, times(2)).publish(sent.capture());
        assertThat(sent.getAllValues()).extracting(InboundMessage::shipmentId)
                .containsOnly("SHP-20261105-AM-S4");
        assertThat(s.status().inboundSent()).containsEntry(4L, "SHP-20261105-AM-S4");
    }

    @Test
    void 주문_결제_픽업준비_픽업까지_순서대로_부른다() {
        // 09:59에 켜서 그날 계획을 다 남긴다
        clock = new MutableClock(ZonedDateTime.of(2026, 11, 5, 9, 59, 0, 0, KST));
        DemoScheduler s = scheduler(props(true, 0));
        s.tick();
        assertThat(s.status().ordersPlanned()).isEqualTo(1);
        assertThat(s.status().ordersPlaced()).isZero();

        server.expect(requestTo("http://inventory/stores/4/products")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"productId":1,"name":"제육볶음 도시락","availableQuantity":0,"soldOut":true,"firstCome":false},
                         {"productId":2,"name":"치킨마요 도시락","availableQuantity":5,"soldOut":false,"firstCome":true},
                         {"productId":12,"name":"흰 우유 500ml","availableQuantity":1,"soldOut":false,"firstCome":false}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://member/stores/4"))
                .andRespond(withSuccess("{\"storeId\":4,\"name\":\"프레시링크 문래역점\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://order/orders")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User-Id", SUB)).andExpect(header("X-User-Role", "CUSTOMER"))
                .andExpect(jsonPath("$.storeId").value(4)).andExpect(jsonPath("$.productId").value(12))
                .andExpect(jsonPath("$.quantity").value(1)) // 남은 1개까지만
                .andExpect(jsonPath("$.storeName").value("프레시링크 문래역점"))
                .andRespond(withSuccess("{\"orderId\":55,\"status\":\"RESERVING\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://order/orders/55")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"orderId\":55,\"status\":\"RESERVING\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://order/orders/55"))
                .andRespond(withSuccess("{\"orderId\":55,\"status\":\"PAYMENT_WAITING\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://order/ops/orders/55/simulated-payment")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"orderId\":55,\"status\":\"PAID\"}", MediaType.APPLICATION_JSON));

        clock.advance(Duration.ofHours(11)); // 20:59: 그날 주문은 모두 시각이 됐다
        s.tick();
        server.verify();
        assertThat(s.status().ordersPaid()).isEqualTo(1);
        assertThat(s.pendingActions()).singleElement().satisfies(a -> {
            assertThat(a.type()).isEqualTo(DemoScheduler.ActionType.READY);
            assertThat(a.orderId()).isEqualTo(55L);
        });

        server.reset();
        server.expect(requestTo("http://order/owner/orders/55/ready")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User-Role", "OWNER")).andExpect(header("X-Store-Id", "4"))
                .andRespond(withSuccess());
        clock.advance(Duration.ofMinutes(21));
        s.tick();
        server.verify();
        assertThat(s.pendingActions()).singleElement()
                .satisfies(a -> assertThat(a.type()).isEqualTo(DemoScheduler.ActionType.PICKUP));

        server.reset();
        server.expect(requestTo("http://order/owner/orders/55/pickup")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());
        clock.advance(Duration.ofMinutes(41));
        s.tick();
        server.verify();
        assertThat(s.pendingActions()).isEmpty();
    }

    @Test
    void 픽업_안_하는_주문은_준비까지만() {
        clock = new MutableClock(ZonedDateTime.of(2026, 11, 5, 9, 59, 0, 0, KST));
        DemoScheduler s = scheduler(props(true, 1.0));
        s.tick();
        server.expect(requestTo("http://inventory/stores/4/products"))
                .andRespond(withSuccess("[{\"productId\":12,\"availableQuantity\":9,\"soldOut\":false,\"firstCome\":false}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://member/stores/4"))
                .andRespond(withSuccess("{\"storeId\":4,\"name\":\"프레시링크 문래역점\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://order/orders"))
                .andRespond(withSuccess("{\"orderId\":56,\"status\":\"PAYMENT_WAITING\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://order/orders/56"))
                .andRespond(withSuccess("{\"orderId\":56,\"status\":\"PAYMENT_WAITING\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://order/ops/orders/56/simulated-payment"))
                .andRespond(withSuccess("{\"orderId\":56,\"status\":\"PAID\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://order/owner/orders/56/ready")).andRespond(withSuccess());

        clock.advance(Duration.ofHours(11));
        s.tick();
        clock.advance(Duration.ofMinutes(21));
        s.tick();

        server.verify();
        assertThat(s.pendingActions()).isEmpty();
    }

    @Test
    void 상품_목록을_못_읽으면_그_주문만_건너뛰고_멈추지_않는다() {
        clock = new MutableClock(ZonedDateTime.of(2026, 11, 5, 9, 59, 0, 0, KST));
        DemoScheduler s = scheduler(props(true, 0));
        s.tick();
        server.expect(requestTo("http://inventory/stores/4/products")).andRespond(withServerError());

        clock.advance(Duration.ofHours(11));
        s.tick();

        server.verify();
        assertThat(s.status().ordersSkipped()).isEqualTo(1);
        assertThat(s.pendingActions()).isEmpty();
    }

    @Test
    void 켜진_시각보다_이른_주문은_건너뛴다() {
        clock = new MutableClock(ZonedDateTime.of(2026, 11, 5, 20, 30, 0, 0, KST));
        DemoScheduler s = scheduler(props(true, 0));

        s.tick(); // 계획 1건은 이미 지났다 → 아무것도 부르지 않는다

        server.verify();
        assertThat(s.status().ordersPlanned()).isEqualTo(1);
        assertThat(s.status().nextOrderAt()).isNull();
    }

    @Test
    void 아침_cron_입고는_먼저_경과_lot을_폐기하고_재고를_보고_모자란_만큼만_넣는다() {
        clock = new MutableClock(ZonedDateTime.of(2026, 11, 10, 9, 20, 0, 0, KST));
        DemoScheduler s = scheduler(props(false, 0, true, 0));
        server.expect(once(), requestTo("http://inventory/owner/inventory/disposals/expired"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Store-Id", "4")).andExpect(header("X-User-Role", "OWNER"))
                .andExpect(header("X-User-Id", "logistics-simulator"))
                .andRespond(withSuccess("{\"lotCount\":12,\"quantity\":140,\"items\":[]}", MediaType.APPLICATION_JSON));
        // 라면(15)은 이미 99개라 넣지 않는다
        server.expect(once(), requestTo("http://inventory/stores/4/products"))
                .andRespond(withSuccess("[{\"productId\":15,\"availableQuantity\":99,\"soldOut\":false,\"firstCome\":false}]",
                        MediaType.APPLICATION_JSON));

        s.inboundToday(true);

        server.verify();
        ArgumentCaptor<InboundMessage> sent = ArgumentCaptor.forClass(InboundMessage.class);
        verify(publisher).publish(sent.capture());
        assertThat(sent.getValue().items()).extracting(i -> i.productCode()).doesNotContain("P015");
    }

    @Test
    void 켜질_때_입고는_폐기하지_않는다() {
        clock = new MutableClock(ZonedDateTime.of(2026, 11, 10, 9, 30, 0, 0, KST));
        DemoScheduler s = scheduler(props(false, 0, true, 0));
        server.expect(once(), requestTo("http://inventory/stores/4/products"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        s.inboundToday(false);

        server.verify(); // 폐기 주소를 부르면 예상 밖 요청으로 실패한다
        verify(publisher, times(1)).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 재고를_못_읽어도_입고는_보낸다() {
        clock = new MutableClock(ZonedDateTime.of(2026, 11, 10, 9, 20, 0, 0, KST));
        DemoScheduler s = scheduler(props(false, 0));
        server.expect(once(), requestTo("http://inventory/stores/4/products")).andRespond(withServerError());

        s.inboundToday(true);

        server.verify();
        verify(publisher, times(1)).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 마감세일_주문은_마감세일_수량이_있는_상품을_마감세일로_산다() {
        clock = new MutableClock(ZonedDateTime.of(2026, 11, 5, 9, 59, 0, 0, KST));
        DemoScheduler s = scheduler(props(true, 0, false, 1.0));
        s.tick();
        server.expect(requestTo("http://inventory/stores/4/products"))
                .andRespond(withSuccess("""
                        [{"productId":1,"availableQuantity":5,"soldOut":false,"firstCome":false,"clearanceQuantity":0},
                         {"productId":2,"availableQuantity":0,"soldOut":false,"firstCome":false,"clearanceQuantity":1}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://member/stores/4"))
                .andRespond(withSuccess("{\"storeId\":4,\"name\":\"프레시링크 문래역점\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://order/orders"))
                .andExpect(jsonPath("$.productId").value(2))
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.clearanceQuantity").value(1))
                .andRespond(withSuccess("{\"orderId\":57,\"status\":\"PAYMENT_WAITING\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://order/orders/57"))
                .andRespond(withSuccess("{\"orderId\":57,\"status\":\"PAYMENT_WAITING\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://order/ops/orders/57/simulated-payment"))
                .andRespond(withSuccess("{\"orderId\":57,\"status\":\"PAID\"}", MediaType.APPLICATION_JSON));

        clock.advance(Duration.ofHours(11));
        s.tick();

        server.verify();
        assertThat(s.status().ordersPaid()).isEqualTo(1);
    }

    @Test
    void 기본_주문은_clearanceQuantity를_보내지_않는다() {
        clock = new MutableClock(ZonedDateTime.of(2026, 11, 5, 9, 59, 0, 0, KST));
        DemoScheduler s = scheduler(props(true, 0, false, 0));
        s.tick();
        server.expect(requestTo("http://inventory/stores/4/products"))
                .andRespond(withSuccess("[{\"productId\":2,\"availableQuantity\":3,\"soldOut\":false,\"firstCome\":false,\"clearanceQuantity\":4}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://member/stores/4"))
                .andRespond(withSuccess("{\"storeId\":4,\"name\":\"프레시링크 문래역점\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://order/orders"))
                .andExpect(jsonPath("$.clearanceQuantity").doesNotExist())
                .andRespond(withSuccess("{\"orderId\":58,\"status\":\"RESERVE_FAILED\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://order/orders/58"))
                .andRespond(withSuccess("{\"orderId\":58,\"status\":\"RESERVE_FAILED\"}", MediaType.APPLICATION_JSON));

        clock.advance(Duration.ofHours(11));
        s.tick();

        server.verify();
    }

    @Test
    void 켜질_때_입고는_오늘_cron이_지난_뒤에만() {
        assertThat(DemoScheduleRunner.cronPassedToday("0 20 9 * * *", ZonedDateTime.of(2026, 11, 10, 8, 50, 0, 0, KST))).isFalse();
        assertThat(DemoScheduleRunner.cronPassedToday("0 20 9 * * *", ZonedDateTime.of(2026, 11, 10, 9, 20, 0, 0, KST))).isTrue();
        assertThat(DemoScheduleRunner.cronPassedToday("0 20 9 * * *", ZonedDateTime.of(2026, 11, 10, 15, 0, 0, 0, KST))).isTrue();
        // 2026-11-14는 토요일: 평일 cron이면 그날 입고 없음
        assertThat(DemoScheduleRunner.cronPassedToday("0 20 9 * * MON-FRI", ZonedDateTime.of(2026, 11, 14, 15, 0, 0, 0, KST))).isFalse();
    }
}

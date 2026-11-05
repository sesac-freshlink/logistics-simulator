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
        return new ScheduleProperties(true, "0 20 9 * * *", false, List.of(4L), SUB + ":4", Duration.ofSeconds(20),
                new ScheduleProperties.Orders(ordersEnabled, 1, 1, LocalTime.of(10, 0), LocalTime.of(20, 0), 0, 0,
                        Duration.ofSeconds(30)),
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

        s.inboundToday();
        s.inboundToday();

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
}

package com.freshlink.simulator.schedule;

import com.freshlink.simulator.config.ScheduleProperties;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 클러스터 안 order·inventory·member를 부른다. gateway가 넣는 X-User-* 헤더를 직접 넣는다(서비스끼리 부르는 방식과 같다).
 * 실패는 부르는 쪽(DemoScheduler)이 로그만 남기고 넘어간다
 */
public class ShopClient {

    static final String USER_ID = "X-User-Id";
    static final String USER_ROLE = "X-User-Role";
    static final String STORE_ID = "X-Store-Id";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /** 폐기 요청자(disposal.requested_by, 36자 안). 점주 화면에서 시뮬레이터가 한 폐기를 가려 볼 수 있게 고정 값 */
    static final String SIMULATOR_USER = "logistics-simulator";

    /**
     * inventory U-10 한 줄에서 쓰는 칸만. availableQuantity = 기본 주문으로 살 수 있는 수량(24시간 안 lot 제외),
     * clearanceQuantity = 마감세일 수량(옛 inventory는 칸이 없어 null)
     */
    public record StoreProduct(Long productId, String name, int availableQuantity, boolean soldOut, boolean firstCome,
                               Integer clearanceQuantity) {

        public int clearance() {
            return clearanceQuantity == null ? 0 : clearanceQuantity;
        }
    }

    /** inventory 경과 lot 한 번에 폐기 응답에서 쓰는 칸만 */
    public record ExpiredDisposal(Integer lotCount, Integer quantity) {
    }

    /** member 점포 상세에서 쓰는 칸만 */
    public record Store(Long storeId, String name) {
    }

    /** order OR-01·OR-02·결제 응답에서 쓰는 칸만 */
    public record OrderView(Long orderId, String orderNo, String status, OffsetDateTime paidAt) {
    }

    private final RestClient order;
    private final RestClient inventory;
    private final RestClient member;

    public ShopClient(RestClient.Builder builder, ScheduleProperties.Services services) {
        this.order = builder.clone().baseUrl(services.order()).build();
        this.inventory = builder.clone().baseUrl(services.inventory()).build();
        this.member = builder.clone().baseUrl(services.member()).build();
    }

    /** 고객 상품 목록 (판매 중인 것만 고르는 건 부르는 쪽) */
    public List<StoreProduct> products(long storeId) {
        StoreProduct[] body = inventory.get().uri("/stores/{storeId}/products", storeId)
                .retrieve().body(StoreProduct[].class);
        return body == null ? List.of() : List.of(body);
    }

    /** 점포 이름 (주문 요청 storeName) */
    public String storeName(long storeId) {
        Store body = member.get().uri("/stores/{storeId}", storeId).retrieve().body(Store.class);
        if (body == null || body.name() == null) {
            throw new IllegalStateException("점포 이름이 없다: storeId=" + storeId);
        }
        return body.name();
    }

    public OrderView createOrder(String customerSub, long storeId, String storeName, long productId, int quantity) {
        return createOrder(customerSub, storeId, storeName, productId, quantity, 0);
    }

    /** clearanceQuantity > 0이면 그만큼 마감세일로 산다(order 06–23시만 받는다) */
    public OrderView createOrder(String customerSub, long storeId, String storeName, long productId, int quantity,
                                 int clearanceQuantity) {
        Map<String, Object> body = clearanceQuantity > 0
                ? Map.of("storeId", storeId, "storeName", storeName, "productId", productId, "quantity", quantity,
                        "clearanceQuantity", clearanceQuantity)
                : Map.of("storeId", storeId, "storeName", storeName, "productId", productId, "quantity", quantity);
        return order.post().uri("/orders")
                .header(IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                .header(USER_ID, customerSub)
                .header(USER_ROLE, "CUSTOMER")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(OrderView.class);
    }

    /** 점주 대신 경과 lot 한 번에 폐기 (inventory S-32, 202). 재고는 DISPOSE 쪽지를 처리할 때 줄어든다 */
    public ExpiredDisposal disposeExpired(long storeId) {
        ExpiredDisposal body = inventory.post().uri("/owner/inventory/disposals/expired")
                .header(USER_ID, SIMULATOR_USER)
                .header(USER_ROLE, "OWNER")
                .header(STORE_ID, Long.toString(storeId))
                .retrieve().body(ExpiredDisposal.class);
        return body == null ? new ExpiredDisposal(0, 0) : body;
    }

    public OrderView getOrder(String customerSub, long orderId) {
        return order.get().uri("/orders/{orderId}", orderId)
                .header(USER_ID, customerSub)
                .header(USER_ROLE, "CUSTOMER")
                .retrieve().body(OrderView.class);
    }

    /** order /ops 결제 완료 (토스 없이 SIM- 결제). order에 app.simulated-payment.enabled=true여야 한다 */
    public OrderView simulatedPayment(long orderId) {
        return order.post().uri("/ops/orders/{orderId}/simulated-payment", orderId)
                .retrieve().body(OrderView.class);
    }

    /** 점주 대신 픽업 준비 완료 (고객 "픽업 준비 완료" 알림) */
    public void ready(long storeId, long orderId) {
        owner(storeId).post().uri("/owner/orders/{orderId}/ready", orderId).retrieve().toBodilessEntity();
    }

    /** 점주 대신 픽업 확인 */
    public void pickUp(long storeId, long orderId) {
        owner(storeId).post().uri("/owner/orders/{orderId}/pickup", orderId).retrieve().toBodilessEntity();
    }

    /** 고객 결제 후 취소 (SIM- 결제는 order가 토스 없이 환불 완료로 처리한다) */
    public void cancel(String customerSub, long orderId) {
        order.post().uri("/orders/{orderId}/cancel", orderId)
                .header(USER_ID, customerSub)
                .header(USER_ROLE, "CUSTOMER")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "CHANGE_OF_MIND"))
                .retrieve().toBodilessEntity();
    }

    private RestClient owner(long storeId) {
        return order.mutate()
                .defaultHeader(USER_ROLE, "OWNER")
                .defaultHeader(STORE_ID, Long.toString(storeId))
                .build();
    }
}

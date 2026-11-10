package com.freshlink.simulator.schedule;

import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.simulator.config.ScheduleProperties;
import com.freshlink.simulator.messaging.InboundPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * prod 데모 매일 시나리오 (3차 1-2). 파드 1개라 ShedLock을 쓰지 않고, DB가 없어 진행 중인 주문은 메모리에만 둔다.
 * 파드가 다시 뜨면 그날 남은 픽업 처리만 빠진다(입고는 고정 shipmentId라 다시 보내도 된다, 지난 시각 주문은 건너뛴다).
 * 시각 판단은 {@link #tick()}이 하고, 스프링 예약은 {@link DemoScheduleRunner}가 이 메서드들을 부른다
 */
public class DemoScheduler {

    private static final Logger log = LoggerFactory.getLogger(DemoScheduler.class);

    enum ActionType { READY, PICKUP, CANCEL }

    static final LocalTime CLEARANCE_OPEN = LocalTime.of(6, 0);
    static final LocalTime CLEARANCE_CLOSE = LocalTime.of(23, 0);

    record Action(LocalDateTime at, ActionType type, long storeId, long orderId, String customerSub, boolean noShow) {
    }

    /** GET /simulator/schedule 응답 */
    public record Status(boolean enabled, boolean ordersEnabled, LocalDate date, int stores, int customers,
                         Map<Long, String> inboundSent, int ordersPlanned, int ordersPlaced, int ordersPaid,
                         int ordersSkipped, int pendingActions, LocalDateTime nextOrderAt,
                         LocalDateTime nextActionAt) {
    }

    private final ScheduleProperties props;
    private final InboundPublisher publisher;
    private final ShopClient shop;
    private final Clock clock;
    private final Customers customers;
    private final List<Long> stores;
    private final Sleeper sleeper;

    private final Map<Long, String> inboundSent = new ConcurrentHashMap<>();   // 점포 → 오늘 shipmentId
    private final Map<Long, String> storeNames = new ConcurrentHashMap<>();
    private final PriorityQueue<Action> actions = new PriorityQueue<>(Comparator.comparing(Action::at));
    private LocalDate today;
    private List<OrderPlanner.PlannedOrder> plan = List.of();
    private int next;
    private int placed;
    private int paid;
    private int skipped;

    /** 결제 대기를 기다릴 때 쉬는 방법(테스트는 바로 돌아온다) */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    public DemoScheduler(ScheduleProperties props, InboundPublisher publisher, ShopClient shop, Clock clock) {
        this(props, publisher, shop, clock, d -> Thread.sleep(d));
    }

    DemoScheduler(ScheduleProperties props, InboundPublisher publisher, ShopClient shop, Clock clock, Sleeper sleeper) {
        this.props = props;
        this.publisher = publisher;
        this.shop = shop;
        this.clock = clock;
        this.sleeper = sleeper;
        this.customers = Customers.parse(props.customers());
        this.stores = props.stores() == null || props.stores().isEmpty() ? customers.stores() : List.copyOf(props.stores());
        if (props.orders().enabled() && customers.all().isEmpty()) {
            log.warn("주문 시나리오가 켜졌는데 가상 고객(SIMULATOR_CUSTOMERS)이 비어 있다. 입고만 한다");
        }
    }

    /** 켜질 때: 오늘 입고만 (폐기는 아침 cron에서만, 하루 중 다시 떠도 어제 마감 시연 lot을 지우지 않게) */
    public void inboundToday() {
        inboundToday(false);
    }

    /**
     * 오늘 입고를 점포마다 한 번 보낸다. 같은 날 다시 불러도 shipmentId가 같아 inventory가 한 번만 받는다.
     * morning이고 disposeExpired면 입고 전에 점주 대신 경과 lot을 한 번에 폐기한다(그 순간 경과 lot만 잡히므로 이번 입고의 어제 마감 lot은 남는다).
     * 입고량은 지금 재고(고객 상품 목록)를 보고 모자란 만큼만 정한다
     */
    public synchronized void inboundToday(boolean morning) {
        LocalDate date = LocalDate.now(clock);
        rollOver(date);
        int disposedLots = 0;
        int disposedQuantity = 0;
        for (long storeId : stores) {
            if (morning && props.disposeExpired()) {
                try {
                    ShopClient.ExpiredDisposal d = shop.disposeExpired(storeId);
                    disposedLots += d.lotCount() == null ? 0 : d.lotCount();
                    disposedQuantity += d.quantity() == null ? 0 : d.quantity();
                } catch (RuntimeException e) {
                    log.warn("경과 lot 폐기 실패 storeId={}: {}", storeId, e.toString());
                }
            }
            try {
                InboundMessage message = InboundPlanner.plan(storeId, date, stock(storeId), props.launch());
                publisher.publish(message);
                inboundSent.put(storeId, message.shipmentId());
            } catch (RuntimeException e) {
                log.warn("오늘 입고 실패 storeId={}: {}", storeId, e.toString());
            }
        }
        if (morning && props.disposeExpired()) {
            log.info("경과 lot 폐기 요청 date={} lots={} quantity={}", date, disposedLots, disposedQuantity);
        }
        log.info("오늘 입고 보냄 date={} stores={}", date, inboundSent.size());
    }

    /** 상품 ID → 기본 주문으로 살 수 있는 수량. 못 읽으면 빈 표(모두 0으로 보고 채운다) */
    private Map<Long, Integer> stock(long storeId) {
        try {
            Map<Long, Integer> stock = new HashMap<>();
            for (ShopClient.StoreProduct p : shop.products(storeId)) {
                if (p.productId() != null) {
                    stock.put(p.productId(), p.availableQuantity());
                }
            }
            return stock;
        } catch (RuntimeException e) {
            log.warn("재고를 못 읽어 0으로 보고 입고한다 storeId={}: {}", storeId, e.toString());
            return Map.of();
        }
    }

    /** 주기마다: 시각이 된 주문을 넣고, 시각이 된 픽업 준비·픽업·취소를 한다 */
    public void tick() {
        LocalDateTime now = LocalDateTime.now(clock);
        rollOver(now.toLocalDate());
        if (props.orders().enabled()) {
            OrderPlanner.PlannedOrder due;
            while ((due = nextDueOrder(now)) != null) {
                place(due);
            }
        }
        Action action;
        while ((action = nextDueAction(LocalDateTime.now(clock))) != null) {
            run(action);
        }
    }

    public synchronized Status status() {
        Action head = actions.peek();
        return new Status(props.enabled(), props.orders().enabled(), today, stores.size(), customers.all().size(),
                Map.copyOf(inboundSent), plan.size(), placed, paid, skipped, actions.size(),
                next < plan.size() ? plan.get(next).at() : null, head == null ? null : head.at());
    }

    /** 날짜가 바뀌면 오늘 계획을 새로 만든다. 이미 지난 시각 주문은 건너뛴다(켜질 때 몰아서 넣지 않게) */
    private synchronized void rollOver(LocalDate date) {
        if (date.equals(today)) {
            return;
        }
        today = date;
        inboundSent.clear();
        plan = OrderPlanner.plan(date, stores, customers, props.orders(), props.pickup());
        LocalDateTime now = LocalDateTime.now(clock);
        next = 0;
        while (next < plan.size() && plan.get(next).at().isBefore(now)) {
            next++;
        }
        placed = paid = skipped = 0;
        log.info("오늘 주문 계획 date={} orders={} (지난 시각 {}건 건너뜀)", date, plan.size(), next);
    }

    private synchronized OrderPlanner.PlannedOrder nextDueOrder(LocalDateTime now) {
        if (next < plan.size() && !plan.get(next).at().isAfter(now)) {
            return plan.get(next++);
        }
        return null;
    }

    private synchronized Action nextDueAction(LocalDateTime now) {
        Action head = actions.peek();
        return head != null && !head.at().isAfter(now) ? actions.poll() : null;
    }

    private synchronized void schedule(Action action) {
        actions.add(action);
    }

    private synchronized void count(boolean wasPlaced, boolean wasPaid) {
        if (wasPlaced) {
            placed++;
        } else {
            skipped++;
        }
        if (wasPaid) {
            paid++;
        }
    }

    /** 주문 하나: 판매 중 상품 고르기 → 주문 → 결제 대기 기다리기 → (대부분) 결제 완료 → 픽업 준비 예약 */
    void place(OrderPlanner.PlannedOrder o) {
        Random random = new Random(o.seed());
        try {
            List<ShopClient.StoreProduct> products = shop.products(o.storeId());
            ShopClient.StoreProduct product = null;
            int quantity = 0;
            int clearanceQuantity = 0;
            if (o.clearance() && inClearanceHours(LocalDateTime.now(clock).toLocalTime())) {
                List<ShopClient.StoreProduct> clearance = products.stream()
                        .filter(p -> !p.firstCome() && p.clearance() > 0)
                        .toList();
                if (!clearance.isEmpty()) {
                    product = clearance.get(random.nextInt(clearance.size()));
                    quantity = Math.min(o.quantity(), product.clearance());
                    clearanceQuantity = quantity;
                }
            }
            if (product == null) {
                List<ShopClient.StoreProduct> onSale = products.stream()
                        .filter(p -> !p.soldOut() && !p.firstCome() && p.availableQuantity() > 0)
                        .toList();
                if (onSale.isEmpty()) {
                    log.info("판매 중인 상품이 없어 주문을 건너뛴다 storeId={}", o.storeId());
                    count(false, false);
                    return;
                }
                product = onSale.get(random.nextInt(onSale.size()));
                quantity = Math.min(o.quantity(), product.availableQuantity());
            }
            String storeName = storeNames.computeIfAbsent(o.storeId(), shop::storeName);
            ShopClient.OrderView created = shop.createOrder(o.customerSub(), o.storeId(), storeName,
                    product.productId(), quantity, clearanceQuantity);
            String status = waitForPayment(o.customerSub(), created.orderId());
            if (!"PAYMENT_WAITING".equals(status)) {
                // 재고 부족(RESERVE_FAILED) 등. 실제로도 생기는 일이라 그대로 둔다
                log.info("결제 대기가 되지 않아 넘어간다 orderId={} status={}", created.orderId(), status);
                count(true, false);
                return;
            }
            if (o.unpaid()) {
                log.info("결제하지 않고 둔다(10분 뒤 EXPIRED) orderId={}", created.orderId());
                count(true, false);
                return;
            }
            shop.simulatedPayment(created.orderId());
            count(true, true);
            LocalDateTime now = LocalDateTime.now(clock);
            if (o.cancel()) {
                schedule(new Action(now.plusMinutes(2 + random.nextInt(9)), ActionType.CANCEL, o.storeId(),
                        created.orderId(), o.customerSub(), false));
            } else {
                schedule(new Action(now.plus(between(random, props.pickup().readyAfterMin(), props.pickup().readyAfterMax())),
                        ActionType.READY, o.storeId(), created.orderId(), o.customerSub(), o.noShow()));
            }
            log.info("시뮬레이터 주문 결제 완료 orderId={} storeId={} productId={} quantity={} clearanceQuantity={}",
                    created.orderId(), o.storeId(), product.productId(), quantity, clearanceQuantity);
        } catch (RuntimeException e) {
            log.warn("시뮬레이터 주문 실패 storeId={} customer={}: {}", o.storeId(), o.customerSub(), e.toString());
            count(false, false);
        }
    }

    /** OR-02를 2초마다 봐서 RESERVING이 끝나면 그 상태를 돌려준다. paymentWait 안에 안 끝나면 마지막 상태 */
    private String waitForPayment(String customerSub, long orderId) {
        long deadline = clock.millis() + props.orders().paymentWait().toMillis();
        while (true) {
            String status = shop.getOrder(customerSub, orderId).status();
            if (!"RESERVING".equals(status) || clock.millis() >= deadline) {
                return status;
            }
            try {
                sleeper.sleep(Duration.ofSeconds(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return status;
            }
        }
    }

    private void run(Action a) {
        try {
            switch (a.type()) {
                case READY -> {
                    shop.ready(a.storeId(), a.orderId());
                    if (!a.noShow()) {
                        Random random = new Random(a.orderId());
                        schedule(new Action(LocalDateTime.now(clock).plus(between(random,
                                props.pickup().doneAfterMin(), props.pickup().doneAfterMax())),
                                ActionType.PICKUP, a.storeId(), a.orderId(), a.customerSub(), false));
                    }
                }
                case PICKUP -> shop.pickUp(a.storeId(), a.orderId());
                case CANCEL -> shop.cancel(a.customerSub(), a.orderId());
            }
            log.info("시뮬레이터 {} orderId={} storeId={}", a.type(), a.orderId(), a.storeId());
        } catch (RuntimeException e) {
            log.warn("시뮬레이터 {} 실패 orderId={}: {}", a.type(), a.orderId(), e.toString());
        }
    }

    /** 마감세일 판매 시간 (KST 06:00 이상 23:00 미만, inventory·order ClearancePolicy와 같다) */
    static boolean inClearanceHours(LocalTime t) {
        return !t.isBefore(CLEARANCE_OPEN) && t.isBefore(CLEARANCE_CLOSE);
    }

    static Duration between(Random random, Duration min, Duration max) {
        long span = Math.max(0, max.toSeconds() - min.toSeconds());
        return min.plusSeconds(span == 0 ? 0 : (long) (random.nextDouble() * (span + 1)));
    }

    List<Action> pendingActions() {
        synchronized (this) {
            return new ArrayList<>(actions);
        }
    }
}

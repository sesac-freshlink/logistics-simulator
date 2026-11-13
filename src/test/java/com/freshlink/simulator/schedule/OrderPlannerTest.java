package com.freshlink.simulator.schedule;

import com.freshlink.simulator.config.ScheduleProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class OrderPlannerTest {

    static final ScheduleProperties.Orders ORDERS = new ScheduleProperties.Orders(true, 3, 6,
            LocalTime.of(10, 0), LocalTime.of(20, 0), 0.05, 0.03, Duration.ofSeconds(30), 0.3);
    static final ScheduleProperties.Pickup PICKUP = new ScheduleProperties.Pickup(Duration.ofMinutes(5),
            Duration.ofMinutes(20), Duration.ofMinutes(10), Duration.ofMinutes(40), 0.1);
    static final Customers CUSTOMERS = Customers.parse("a:1,2;b:2;c:3");
    static final LocalDate DAY = LocalDate.of(2026, 11, 5);

    @Test
    void 점포마다_3에서_6건_주문_시간_안에_단골_고객으로() {
        List<OrderPlanner.PlannedOrder> plan = OrderPlanner.plan(DAY, List.of(1L, 2L, 3L, 99L), CUSTOMERS, ORDERS, PICKUP);

        Map<Long, Long> perStore = plan.stream().collect(Collectors.groupingBy(OrderPlanner.PlannedOrder::storeId,
                Collectors.counting()));
        assertThat(perStore).doesNotContainKey(99L); // 단골이 없는 점포는 주문이 없다
        assertThat(perStore.values()).allSatisfy(n -> assertThat(n).isBetween(3L, 6L));
        assertThat(plan).allSatisfy(o -> {
            assertThat(o.at().toLocalDate()).isEqualTo(DAY);
            assertThat(o.at().toLocalTime()).isBetween(LocalTime.of(10, 0), LocalTime.of(19, 59, 59));
            assertThat(o.quantity()).isBetween(1, 3);
            assertThat(CUSTOMERS.of(o.storeId())).extracting(Customers.Customer::sub).contains(o.customerSub());
            assertThat(o.unpaid() && o.cancel()).isFalse();
        });
        assertThat(plan).isSortedAccordingTo((x, y) -> x.at().compareTo(y.at()));
    }

    @Test
    void 같은_날이면_같은_계획() {
        assertThat(OrderPlanner.plan(DAY, List.of(1L, 2L, 3L), CUSTOMERS, ORDERS, PICKUP))
                .isEqualTo(OrderPlanner.plan(DAY, List.of(1L, 2L, 3L), CUSTOMERS, ORDERS, PICKUP));
    }

    @Test
    void 점심_저녁에_더_몰린다() {
        Random random = new Random(1);
        int peak = 0;
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            LocalTime t = OrderPlanner.pickTime(random, LocalTime.of(10, 0), LocalTime.of(20, 0));
            if (OrderPlanner.weight(t.toSecondOfDay() / 60) == 2) {
                peak++;
            }
        }
        // 10시간 중 4시간이 무게 2 → 8 / 14 ≈ 57%
        assertThat(peak / (double) n).isBetween(0.53, 0.61);
    }
}

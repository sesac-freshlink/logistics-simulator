package com.freshlink.simulator.schedule;

import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class InboundPlannerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 5);
    private static final Map<String, Catalog.Product> BY_CODE = Catalog.PRODUCTS.stream()
            .collect(Collectors.toMap(Catalog.Product::code, Function.identity()));

    @Test
    void shipmentId는_날짜와_점포로_고정된다() {
        assertThat(InboundPlanner.shipmentId(4, DAY)).isEqualTo("SHP-20261105-AM-S4");
        assertThat(InboundPlanner.plan(4, DAY).shipmentId()).isEqualTo("SHP-20261105-AM-S4");
    }

    @Test
    void 같은_점포_같은_날이면_같은_입고가_나온다() {
        InboundMessage a = InboundPlanner.plan(7, DAY);
        InboundMessage b = InboundPlanner.plan(7, DAY);
        assertThat(a.items()).isEqualTo(b.items());
        assertThat(InboundPlanner.plan(8, DAY).items()).isNotEqualTo(a.items());
    }

    @Test
    void 상품_20에서_30개_수량_5에서_30개_오늘_마감_1에서_2개_어제_마감_1개() {
        for (long store = 1; store <= 30; store++) {
            for (int d = 0; d < 7; d++) {
                LocalDate day = DAY.plusDays(d);
                InboundMessage m = InboundPlanner.plan(store, day);
                assertThat(m.storeId()).isEqualTo(store);
                assertThat(m.items()).hasSizeBetween(20, 30);
                assertThat(m.items()).extracting(InboundItem::productCode).doesNotHaveDuplicates();
                long today = m.items().stream().filter(i -> i.expiryDate().toLocalDate().equals(day)).count();
                long yesterday = m.items().stream().filter(i -> i.expiryDate().toLocalDate().equals(day.minusDays(1))).count();
                assertThat(yesterday).isEqualTo(1);
                assertThat(today).isGreaterThanOrEqualTo(1);
                for (InboundItem item : m.items()) {
                    assertThat(item.quantity()).isBetween(5, 30);
                    assertThat(item.expiryDate().toLocalTime()).isEqualTo(LocalTime.of(23, 59, 59));
                    assertThat(item.expiryDate().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
                    long days = ChronoUnit.DAYS.between(day, item.expiryDate().toLocalDate());
                    Catalog.Product p = BY_CODE.get(item.productCode());
                    // 오늘·어제 마감으로 뽑힌 것 말고는 분류별 범위 안
                    assertThat(days == 0 || days == -1 || (days >= p.minDays() && days <= p.maxDays())).isTrue();
                }
            }
        }
    }
}

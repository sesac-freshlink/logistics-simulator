package com.freshlink.simulator.schedule;

import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.simulator.config.ScheduleProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class InboundPlannerTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private static final Map<String, Catalog.Product> BY_CODE = Stream.concat(Catalog.PRODUCTS.stream(), Catalog.LAUNCH.stream())
            .collect(Collectors.toMap(Catalog.Product::code, Function.identity()));
    private static final ScheduleProperties.Launch LAUNCH =
            new ScheduleProperties.Launch(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 19), 3);

    @Test
    void shipmentId는_날짜와_점포로_고정된다() {
        assertThat(InboundPlanner.shipmentId(4, DAY)).isEqualTo("SHP-20261110-AM-S4");
        assertThat(InboundPlanner.plan(4, DAY).shipmentId()).isEqualTo("SHP-20261110-AM-S4");
    }

    @Test
    void 같은_점포_같은_날_같은_재고면_같은_입고가_나온다() {
        InboundMessage a = InboundPlanner.plan(7, DAY);
        InboundMessage b = InboundPlanner.plan(7, DAY);
        assertThat(a.items()).isEqualTo(b.items());
        assertThat(InboundPlanner.plan(8, DAY).items()).isNotEqualTo(a.items());
    }

    @Test
    void 빈_점포도_하루_판매량_수준만_넣고_오늘_마감_1에서_2개_어제_마감_1개() {
        for (long store = 1; store <= 30; store++) {
            for (int d = 0; d < 7; d++) {
                LocalDate day = DAY.plusDays(d);
                InboundMessage m = InboundPlanner.plan(store, day);
                assertThat(m.storeId()).isEqualTo(store);
                assertThat(m.items()).extracting(InboundItem::productCode).doesNotHaveDuplicates();
                long today = m.items().stream().filter(i -> i.expiryDate().toLocalDate().equals(day)).count();
                long yesterday = m.items().stream().filter(i -> i.expiryDate().toLocalDate().equals(day.minusDays(1))).count();
                assertThat(yesterday).isEqualTo(1);
                assertThat(today).isBetween(1L, 2L);
                int total = m.items().stream().mapToInt(InboundItem::quantity).sum();
                assertThat(total).isBetween(40, 160); // 예전에는 20–30개 × 5–30개(평균 약 440)
                for (InboundItem item : m.items()) {
                    assertThat(item.quantity()).isBetween(1, 8);
                    assertThat(item.expiryDate().toLocalTime()).isEqualTo(LocalTime.of(23, 59, 59));
                    assertThat(item.expiryDate().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
                    long days = ChronoUnit.DAYS.between(day, item.expiryDate().toLocalDate());
                    Catalog.Product p = BY_CODE.get(item.productCode());
                    assertThat(p).isNotNull();
                    assertThat(p.code()).isLessThanOrEqualTo("P030"); // 기간이 없으면 신상품 없음
                    assertThat(days == 0 || days == -1 || (days >= p.minDays() && days <= p.maxDays())).isTrue();
                    if (p.shortShelf() && days > 0) {
                        assertThat(days).isGreaterThanOrEqualTo(1); // 입고 때부터 마감세일이 되지 않게
                    }
                }
            }
        }
    }

    @Test
    void 재고가_목표_이상이면_그_상품은_넣지_않는다() {
        Map<Long, Integer> full = new HashMap<>();
        for (Catalog.Product p : Catalog.PRODUCTS) {
            full.put(p.id(), 50);
        }
        InboundMessage m = InboundPlanner.plan(5, DAY, full, null);
        // 시연용 오늘·어제 마감 lot만 남는다
        assertThat(m.items()).hasSizeBetween(2, 3);
        assertThat(m.items()).allSatisfy(i -> assertThat(ChronoUnit.DAYS.between(DAY, i.expiryDate().toLocalDate())).isBetween(-1L, 0L));
    }

    @Test
    void 신상품은_점포마다_3개를_기간_안_정해진_날_처음_넣고_그_뒤로_계속_채운다() {
        for (long store = 1; store <= 30; store++) {
            List<Catalog.Product> launched = InboundPlanner.launched(store, LAUNCH);
            assertThat(launched).hasSize(3).doesNotHaveDuplicates();
            for (Catalog.Product p : launched) {
                LocalDate first = InboundPlanner.launchDate(store, p, LAUNCH);
                assertThat(first).isBetween(LAUNCH.from(), LAUNCH.to());
                // 첫날 전에는 없다
                assertThat(InboundPlanner.plan(store, first.minusDays(1), Map.of(), LAUNCH).items())
                        .extracting(InboundItem::productCode).doesNotContain(p.code());
                // 첫날에는 4–6개
                InboundItem firstItem = InboundPlanner.plan(store, first, Map.of(), LAUNCH).items().stream()
                        .filter(i -> i.productCode().equals(p.code())).findFirst().orElseThrow();
                assertThat(firstItem.quantity()).isBetween(4, 6);
                // 시연일(11/20)에도 재고가 없으면 채운다
                assertThat(InboundPlanner.plan(store, LocalDate.of(2026, 11, 20), Map.of(), LAUNCH).items())
                        .extracting(InboundItem::productCode).contains(p.code());
            }
        }
    }

    @Test
    void 기간이_비면_신상품을_넣지_않는다() {
        assertThat(InboundPlanner.launched(1, new ScheduleProperties.Launch(null, null, 3))).isEmpty();
        assertThat(InboundPlanner.launched(1, null)).isEmpty();
    }
}

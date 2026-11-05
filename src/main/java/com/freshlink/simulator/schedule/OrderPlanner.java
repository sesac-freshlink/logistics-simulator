package com.freshlink.simulator.schedule;

import com.freshlink.simulator.config.ScheduleProperties;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 하루 주문 계획. 난수 씨앗이 날짜라 같은 날 다시 만들어도 같은 계획이 나온다(파드가 다시 떠도 지난 시각 주문은 건너뛴다).
 * 점포마다 perStoreMin–Max건, 시각은 주문 시간 안에서 점심(11:30–13:30)·저녁(17:30–19:30)에 두 배로 몰린다.
 */
final class OrderPlanner {

    static final LocalTime LUNCH_START = LocalTime.of(11, 30);
    static final LocalTime LUNCH_END = LocalTime.of(13, 30);
    static final LocalTime DINNER_START = LocalTime.of(17, 30);
    static final LocalTime DINNER_END = LocalTime.of(19, 30);

    record PlannedOrder(int seq, LocalDateTime at, long storeId, String customerSub, int quantity,
                        boolean unpaid, boolean noShow, boolean cancel, long seed) {
    }

    private OrderPlanner() {
    }

    static List<PlannedOrder> plan(LocalDate date, List<Long> stores, Customers customers,
                                   ScheduleProperties.Orders orders, ScheduleProperties.Pickup pickup) {
        Random random = new Random(date.toEpochDay() * 31L + 7);
        List<PlannedOrder> plan = new ArrayList<>();
        int seq = 0;
        for (long storeId : stores) {
            List<Customers.Customer> regulars = customers.of(storeId);
            if (regulars.isEmpty()) {
                continue;
            }
            int span = Math.max(0, orders.perStoreMax() - orders.perStoreMin());
            int count = orders.perStoreMin() + random.nextInt(span + 1);
            for (int i = 0; i < count; i++) {
                Customers.Customer customer = regulars.get(random.nextInt(regulars.size()));
                LocalTime time = pickTime(random, orders.windowStart(), orders.windowEnd());
                boolean unpaid = random.nextDouble() < orders.unpaidRate();
                boolean cancel = !unpaid && random.nextDouble() < orders.cancelRate();
                boolean noShow = !unpaid && !cancel && random.nextDouble() < pickup.noShowRate();
                plan.add(new PlannedOrder(seq++, date.atTime(time), storeId, customer.sub(), 1 + random.nextInt(3),
                        unpaid, noShow, cancel, random.nextLong()));
            }
        }
        plan.sort(Comparator.comparing(PlannedOrder::at).thenComparing(PlannedOrder::seq));
        return List.copyOf(plan);
    }

    /** 주문 시간 안의 한 시각. 점심·저녁 시간대는 무게 2, 나머지는 1 (분 단위로 고른 뒤 초를 붙인다) */
    static LocalTime pickTime(Random random, LocalTime start, LocalTime end) {
        int from = start.toSecondOfDay() / 60;
        int to = end.toSecondOfDay() / 60;
        if (to <= from) {
            return start;
        }
        int total = 0;
        for (int m = from; m < to; m++) {
            total += weight(m);
        }
        int pick = random.nextInt(total);
        for (int m = from; m < to; m++) {
            pick -= weight(m);
            if (pick < 0) {
                return LocalTime.ofSecondOfDay(m * 60L + random.nextInt(60));
            }
        }
        return start;
    }

    static int weight(int minuteOfDay) {
        LocalTime t = LocalTime.ofSecondOfDay(minuteOfDay * 60L);
        boolean peak = (!t.isBefore(LUNCH_START) && t.isBefore(LUNCH_END))
                || (!t.isBefore(DINNER_START) && t.isBefore(DINNER_END));
        return peak ? 2 : 1;
    }
}

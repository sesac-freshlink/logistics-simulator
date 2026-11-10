package com.freshlink.simulator.schedule;

import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.simulator.config.ScheduleProperties;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 점포·날짜마다 아침 입고를 정한다. 난수 씨앗이 (점포, 날짜)라 같은 재고면 같은 날 다시 만들어도 같은 값이 나온다.
 * 11/10: 판매량(점포당 하루 주문 몇 건)보다 훨씬 많이 들어와 경과 lot이 쌓이던 것을 "모자란 만큼 채우기"로 바꿨다.
 * <ul>
 *   <li>상품마다 목표 진열 수량을 정하고, 지금 기본 주문으로 살 수 있는 수량(고객 상품 목록 availableQuantity)을 뺀 만큼만 입고한다.
 *       도시락·김밥·샌드위치·샐러드는 그날 40% 정도만 1–3개, 빵·유제품·디저트·과일·신선은 2–4개, 오래가는 상품은 4–8개</li>
 *   <li>1–2개는 오늘 마감(2–4개) → 임박 알림·마감세일, 1개는 어제 마감(1–3개) → 판매에서 빠지고 점주 경과 탭·폐기(11/5 태우님 답)</li>
 *   <li>시연용 신상품(Catalog.LAUNCH)은 점포마다 정해진 날 처음 들어온다({@link #launchDate}). 첫날은 4–6개</li>
 *   <li>shipmentId = SHP-yyyyMMdd-AM-S점포: 같은 날 두 번 보내도 inventory가 한 번만 받는다</li>
 * </ul>
 */
final class InboundPlanner {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);
    static final double SHORT_SHELF_CARRY_RATE = 0.4;

    private InboundPlanner() {
    }

    static String shipmentId(long storeId, LocalDate date) {
        return "SHP-" + date.format(DAY) + "-AM-S" + storeId;
    }

    /** 재고를 모를 때(목록을 못 읽음): 모두 0으로 보고 채운다 */
    static InboundMessage plan(long storeId, LocalDate date) {
        return plan(storeId, date, Map.of(), null);
    }

    /**
     * @param stock  상품 ID → 지금 기본 주문으로 살 수 있는 수량 (없는 상품은 0)
     * @param launch 신상품 첫 입고 기간 (null이거나 꺼져 있으면 신상품 없음)
     */
    static InboundMessage plan(long storeId, LocalDate date, Map<Long, Integer> stock, ScheduleProperties.Launch launch) {
        Random random = new Random(storeId * 1_000_003L + date.toEpochDay());
        List<Catalog.Product> catalog = new ArrayList<>(Catalog.PRODUCTS);
        Set<String> launchingToday = new HashSet<>();
        for (Catalog.Product p : launched(storeId, launch)) {
            LocalDate first = launchDate(storeId, p, launch);
            if (!date.isBefore(first)) {
                catalog.add(p);
                if (date.equals(first)) {
                    launchingToday.add(p.code());
                }
            }
        }
        Collections.shuffle(catalog, random);

        List<InboundItem> items = new ArrayList<>();
        Set<String> used = new HashSet<>();
        // 임박·경과 시연용 lot: 기한이 짧은 상품(신상품 제외)에서 고른다
        List<Catalog.Product> demo = catalog.stream()
                .filter(p -> p.perishable() && !launchingToday.contains(p.code()))
                .toList();
        int todayLots = 1 + random.nextInt(2);
        for (int i = 0; i < todayLots + 1 && i < demo.size(); i++) {
            Catalog.Product p = demo.get(i);
            boolean today = i < todayLots;
            int quantity = today ? 2 + random.nextInt(3) : 1 + random.nextInt(3);
            items.add(item(p, quantity, today ? date : date.minusDays(1)));
            used.add(p.code());
        }

        for (Catalog.Product p : catalog) {
            if (used.contains(p.code())) {
                continue;
            }
            int target;
            if (launchingToday.contains(p.code())) {
                target = 4 + random.nextInt(3);
            } else if (p.shortShelf()) {
                if (random.nextDouble() >= SHORT_SHELF_CARRY_RATE) {
                    continue;
                }
                target = 1 + random.nextInt(3);
            } else if (p.perishable()) {
                target = 2 + random.nextInt(3);
            } else {
                target = 4 + random.nextInt(5);
            }
            int quantity = target - Math.max(0, stock.getOrDefault(p.id(), 0));
            if (quantity <= 0) {
                continue;
            }
            LocalDate expiry = date.plusDays(p.minDays() + random.nextInt(p.maxDays() - p.minDays() + 1));
            items.add(item(p, quantity, expiry));
        }
        return InboundMessage.of(storeId, shipmentId(storeId, date), items);
    }

    /** 이 점포가 받을 신상품 perStore개 (점포마다 고정) */
    static List<Catalog.Product> launched(long storeId, ScheduleProperties.Launch launch) {
        if (launch == null || !launch.enabled()) {
            return List.of();
        }
        List<Catalog.Product> products = new ArrayList<>(Catalog.LAUNCH);
        Collections.shuffle(products, new Random(storeId * 7_919L + 13));
        return List.copyOf(products.subList(0, Math.min(launch.perStore(), products.size())));
    }

    /** 신상품이 이 점포에 처음 들어오는 날: from–to 사이에 점포·상품마다 나눈다 */
    static LocalDate launchDate(long storeId, Catalog.Product product, ScheduleProperties.Launch launch) {
        long span = ChronoUnit.DAYS.between(launch.from(), launch.to()) + 1;
        long offset = Math.floorMod(storeId * 5 + product.id() * 3, span);
        return launch.from().plusDays(offset);
    }

    private static InboundItem item(Catalog.Product p, int quantity, LocalDate expiry) {
        return new InboundItem(p.code(), quantity, expiry.atTime(END_OF_DAY).atOffset(MessageJson.KST));
    }
}

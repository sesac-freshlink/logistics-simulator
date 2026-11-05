package com.freshlink.simulator.schedule;

import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 점포·날짜마다 아침 입고를 정한다. 난수 씨앗이 (점포, 날짜)라 같은 날 다시 만들어도 같은 값이 나온다.
 * <ul>
 *   <li>30개 상품 중 20–30개, 수량 5–30개, 유통기한 = 오늘 + 분류별 며칠 (23:59:59 KST)</li>
 *   <li>1–2개는 오늘 마감 → 첫 회차에 임박 알림, 1개는 어제 마감 → 판매에서 빠지고 다음 회차에 경과 알림(11/5 태우님 답)</li>
 *   <li>shipmentId = SHP-yyyyMMdd-AM-S점포: 같은 날 두 번 보내도 inventory가 한 번만 받는다</li>
 * </ul>
 */
final class InboundPlanner {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    private InboundPlanner() {
    }

    static String shipmentId(long storeId, LocalDate date) {
        return "SHP-" + date.format(DAY) + "-AM-S" + storeId;
    }

    static InboundMessage plan(long storeId, LocalDate date) {
        Random random = new Random(storeId * 1_000_003L + date.toEpochDay());
        List<Catalog.Product> products = new ArrayList<>(Catalog.PRODUCTS);
        Collections.shuffle(products, random);
        int count = 20 + random.nextInt(11);              // 20–30개
        List<Catalog.Product> picked = products.subList(0, count);
        int todayLots = 1 + random.nextInt(2);             // 오늘 마감 1–2개

        List<InboundItem> items = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            Catalog.Product p = picked.get(i);
            LocalDate expiry;
            if (i < todayLots) {
                expiry = date;                              // 임박 알림용
            } else if (i == todayLots) {
                expiry = date.minusDays(1);                 // 경과 알림·폐기 화면용
            } else {
                expiry = date.plusDays(p.minDays() + random.nextInt(p.maxDays() - p.minDays() + 1));
            }
            int quantity = 5 + random.nextInt(26);          // 5–30개
            items.add(new InboundItem(p.code(), quantity, expiry.atTime(END_OF_DAY).atOffset(MessageJson.KST)));
        }
        return InboundMessage.of(storeId, shipmentId(storeId, date), items);
    }
}

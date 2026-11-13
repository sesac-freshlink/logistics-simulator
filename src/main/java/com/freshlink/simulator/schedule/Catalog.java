package com.freshlink.simulator.schedule;

import java.util.List;

/**
 * 상품 30개와 분류별 유통기한(입고일 + 며칠). 상품 코드·분류는 infra scripts/seed/prod-inventory.sql과 같다(dev도 같은 30개).
 * {@link #LAUNCH}는 시연용 신상품 6개(P031–P036, 11/10 추가). infra 시드에 상품 행이 있어야 하고,
 * simulator.schedule.launch 기간을 정한 환경에서만 입고한다(상품 행이 없는 환경에 보내면 그 점포 입고가 통째로 실패한다).
 * 상품 ID = 코드 숫자(P001 → 1)다. 시드가 그렇게 넣는다.
 */
final class Catalog {

    record Product(String code, String category, int minDays, int maxDays) {

        /** 시드 상품 ID (P012 → 12) */
        long id() {
            return Long.parseLong(code.substring(1));
        }

        /** 하루 이틀이면 기한이 끝나는 상품(도시락·김밥·샌드위치·샐러드) */
        boolean shortShelf() {
            return maxDays <= 2;
        }

        /** 열흘 안에 기한이 끝나는 상품(빵·유제품·디저트·과일·신선식품 포함) */
        boolean perishable() {
            return maxDays <= 10;
        }
    }

    static final List<Product> PRODUCTS = List.of(
            p("P001", "도시락"), p("P002", "도시락"), p("P003", "도시락"),
            p("P004", "김밥"), p("P005", "김밥"), p("P006", "김밥"),
            p("P007", "샌드위치"), p("P008", "샌드위치"), p("P009", "샌드위치"),
            p("P010", "샐러드"), p("P011", "샐러드"),
            p("P012", "유제품"), p("P013", "유제품"), p("P014", "유제품"),
            p("P015", "라면"), p("P016", "라면"),
            p("P017", "빵"), p("P018", "빵"), p("P019", "빵"),
            p("P020", "디저트"), p("P021", "디저트"),
            p("P022", "과일"), p("P023", "과일"),
            p("P024", "음료"), p("P025", "음료"),
            p("P026", "신선식품"), p("P027", "신선식품"), p("P028", "신선식품"),
            p("P029", "즉석식품"), p("P030", "즉석식품"));

    /** 시연용 신상품 (점포마다 launch 기간에 처음 입고 → 고객 화면 신상품 배지 7일) */
    static final List<Product> LAUNCH = List.of(
            p("P031", "디저트"), p("P032", "음료"), p("P033", "빵"),
            p("P034", "라면"), p("P035", "음료"), p("P036", "즉석식품"));

    private Catalog() {
    }

    /**
     * 분류별 유통기한. 도시락·김밥·샌드위치·샐러드는 1–2일(11/10: 0일이면 입고 때부터 24시간 안이라 마감세일로만 팔려서 1일부터), 
     * 유제품·디저트 3–10, 빵 2–4, 과일·신선 3–7, 음료 30–90, 라면·즉석 90–180
     */
    private static Product p(String code, String category) {
        return switch (category) {
            case "도시락", "김밥", "샌드위치", "샐러드" -> new Product(code, category, 1, 2);
            case "유제품", "디저트" -> new Product(code, category, 3, 10);
            case "빵" -> new Product(code, category, 2, 4);
            case "과일", "신선식품" -> new Product(code, category, 3, 7);
            case "음료" -> new Product(code, category, 30, 90);
            case "라면", "즉석식품" -> new Product(code, category, 90, 180);
            default -> throw new IllegalArgumentException("분류 없음: " + category);
        };
    }
}

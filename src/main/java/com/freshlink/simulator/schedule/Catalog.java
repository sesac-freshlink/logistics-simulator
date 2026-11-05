package com.freshlink.simulator.schedule;

import java.util.List;

/**
 * 상품 30개와 분류별 유통기한(입고일 + 며칠). 상품 코드·분류는 infra scripts/seed/prod-inventory.sql과 같다(dev도 같은 30개).
 */
final class Catalog {

    record Product(String code, String category, int minDays, int maxDays) {
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

    private Catalog() {
    }

    /** 분류별 유통기한 (3차 문서 1-2): 도시락·김밥·샌드위치·샐러드 0–1일, 유제품·디저트 3–10, 빵 2–4, 과일·신선 3–7, 음료 30–90, 라면·즉석 90–180 */
    private static Product p(String code, String category) {
        return switch (category) {
            case "도시락", "김밥", "샌드위치", "샐러드" -> new Product(code, category, 0, 1);
            case "유제품", "디저트" -> new Product(code, category, 3, 10);
            case "빵" -> new Product(code, category, 2, 4);
            case "과일", "신선식품" -> new Product(code, category, 3, 7);
            case "음료" -> new Product(code, category, 30, 90);
            case "라면", "즉석식품" -> new Product(code, category, 90, 180);
            default -> throw new IllegalArgumentException("분류 없음: " + category);
        };
    }
}

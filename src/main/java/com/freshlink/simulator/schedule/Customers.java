package com.freshlink.simulator.schedule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 가상 고객 목록. 값은 "sub:4,7;sub:5" (infra scripts/seed-cognito.ps1이 Secrets Manager에 넣고 External Secrets가 환경 변수로 넣는다).
 * 비밀번호는 없다. 시뮬레이터는 X-User-Id = sub로 order를 부른다
 */
record Customers(List<Customer> all) {

    record Customer(String sub, List<Long> stores) {
    }

    static Customers parse(String value) {
        List<Customer> list = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return new Customers(List.of());
        }
        for (String entry : value.split(";")) {
            String e = entry.trim();
            int colon = e.indexOf(':');
            if (colon <= 0 || colon == e.length() - 1) {
                continue;
            }
            List<Long> stores = Arrays.stream(e.substring(colon + 1).split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).map(Long::valueOf).toList();
            list.add(new Customer(e.substring(0, colon).trim(), stores));
        }
        return new Customers(List.copyOf(list));
    }

    /** 그 점포를 단골로 둔 고객 */
    List<Customer> of(long storeId) {
        return all.stream().filter(c -> c.stores().contains(storeId)).toList();
    }

    /** 고객이 단골로 둔 점포 전부 (설정 stores가 비었을 때 대상 점포) */
    List<Long> stores() {
        return all.stream().flatMap(c -> c.stores().stream()).distinct().sorted().toList();
    }
}

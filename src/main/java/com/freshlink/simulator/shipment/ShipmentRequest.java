package com.freshlink.simulator.shipment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.util.List;

/**
 * 입고 요청. 필드를 비우면 시연 프리셋 값을 쓴다 (본문 없이 보내도 된다).
 *
 * @param storeIds 입고할 점포 ID 목록. 비우면 프리셋 점포(1, 2, 3)
 * @param items    품목 목록. 비우면 프리셋 품목(컵라면 20, 우유 10)
 */
public record ShipmentRequest(List<@NotNull @Positive Long> storeIds, @Valid List<Item> items) {

    public record Item(@NotBlank String productCode, @Min(1) int quantity, @NotNull LocalDate expiryDate) {
    }
}

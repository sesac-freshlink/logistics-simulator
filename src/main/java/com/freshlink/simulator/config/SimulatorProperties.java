package com.freshlink.simulator.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code simulator.*} 설정.
 *
 * @param queue  INBOUND를 보낼 FIFO 큐 이름. 예: dev-inventory-commands.fifo
 * @param preset 요청 본문이 없을 때 보내는 시연 입고
 */
@Validated
@ConfigurationProperties("simulator")
public record SimulatorProperties(@NotBlank String queue, @Valid @NotNull Preset preset) {

    public record Preset(@NotEmpty List<Long> storeIds, @Valid @NotEmpty List<Item> items) {
    }

    public record Item(@NotBlank String productCode, @Min(1) int quantity, @NotNull LocalDate expiryDate) {
    }
}

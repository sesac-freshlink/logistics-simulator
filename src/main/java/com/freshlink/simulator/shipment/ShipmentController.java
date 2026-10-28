package com.freshlink.simulator.shipment;

import com.freshlink.simulator.config.SimulatorProperties;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 입고 실행 API. gateway에 라우트가 없어서 밖에서는 닿지 않는다. 시연 때는 port-forward로 연다 (README 5장).
 */
@RestController
@RequestMapping("/simulator")
public class ShipmentController {

    private final ShipmentService shipmentService;
    private final SimulatorProperties properties;

    public ShipmentController(ShipmentService shipmentService, SimulatorProperties properties) {
        this.shipmentService = shipmentService;
        this.properties = properties;
    }

    /** 본문 없이 보내면 시연 프리셋(점포 1·2·3에 컵라면 20, 우유 10) */
    @PostMapping("/shipments")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ShipmentResponse ship(@Valid @RequestBody(required = false) ShipmentRequest request) {
        return shipmentService.ship(request);
    }

    /** 프리셋과 보낼 큐 확인 */
    @GetMapping("/preset")
    public SimulatorProperties preset() {
        return properties;
    }
}

package com.freshlink.simulator.shipment;

import java.util.List;

/** 보낸 INBOUND 쪽지 목록. 점포마다 하나 */
public record ShipmentResponse(String queue, List<Sent> shipments) {

    public record Sent(Long storeId, String shipmentId, String messageId, int itemCount) {
    }
}

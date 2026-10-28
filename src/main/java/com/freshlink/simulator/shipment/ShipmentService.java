package com.freshlink.simulator.shipment;

import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.InboundItem;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.simulator.config.SimulatorProperties;
import com.freshlink.simulator.messaging.InboundPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 요청(또는 프리셋)으로 점포마다 INBOUND 쪽지를 하나씩 만들어 보낸다 */
@Service
public class ShipmentService {

    private static final DateTimeFormatter SHIPMENT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    private final InboundPublisher publisher;
    private final SimulatorProperties.Preset preset;
    private final Clock clock;

    public ShipmentService(InboundPublisher publisher, SimulatorProperties properties, Clock clock) {
        this.publisher = publisher;
        this.preset = properties.preset();
        this.clock = clock;
    }

    public ShipmentResponse ship(ShipmentRequest request) {
        List<Long> storeIds = request == null || request.storeIds() == null || request.storeIds().isEmpty()
                ? preset.storeIds() : request.storeIds();
        List<InboundItem> items = request == null || request.items() == null || request.items().isEmpty()
                ? preset.items().stream().map(i -> item(i.productCode(), i.quantity(), i.expiryDate())).toList()
                : request.items().stream().map(i -> item(i.productCode(), i.quantity(), i.expiryDate())).toList();

        String time = ZonedDateTime.now(clock).format(SHIPMENT_TIME);
        List<ShipmentResponse.Sent> sent = new ArrayList<>();
        for (Long storeId : storeIds.stream().distinct().toList()) {
            // shipmentId는 점포·시각마다 유일하다. 예: SHP-20261017-053012123-S1
            InboundMessage message = InboundMessage.of(storeId, "SHP-" + time + "-S" + storeId, items);
            publisher.publish(message);
            sent.add(new ShipmentResponse.Sent(storeId, message.shipmentId(), message.messageId(), items.size()));
        }
        return new ShipmentResponse(publisher.queue(), sent);
    }

    private static InboundItem item(String productCode, int quantity, LocalDate expiryDate) {
        return new InboundItem(productCode, quantity, expiryDate.atTime(END_OF_DAY).atOffset(MessageJson.KST));
    }
}

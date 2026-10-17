package com.freshlink.simulator.messaging;

import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.simulator.config.SimulatorProperties;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * INBOUND 쪽지를 inventory-commands.fifo로 바로 보낸다. DB가 없어 Outbox를 쓰지 않는다.
 * 같은 쪽지가 두 번 가도 inventory-service가 messageId(멱등)와 shipmentId로 거른다.
 */
@Component
public class InboundPublisher {

    private static final Logger log = LoggerFactory.getLogger(InboundPublisher.class);

    private final SqsTemplate sqsTemplate;
    private final String queue;

    public InboundPublisher(SqsTemplate sqsTemplate, SimulatorProperties properties) {
        this.sqsTemplate = sqsTemplate;
        this.queue = properties.queue();
    }

    public void publish(InboundMessage message) {
        sqsTemplate.send(to -> to.queue(queue)
                .payload(MessageJson.toJson(message))
                .messageGroupId(message.messageGroupId())          // storeId
                .messageDeduplicationId(message.deduplicationId())); // messageId
        log.info("INBOUND 전송: queue={}, storeId={}, shipmentId={}, messageId={}, items={}",
                queue, message.storeId(), message.shipmentId(), message.messageId(), message.items().size());
    }

    public String queue() {
        return queue;
    }
}

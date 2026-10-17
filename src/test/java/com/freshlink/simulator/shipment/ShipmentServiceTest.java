package com.freshlink.simulator.shipment;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.simulator.config.SimulatorProperties;
import com.freshlink.simulator.messaging.InboundPublisher;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 실제 SqsTemplate에 AWS SDK 클라이언트만 목으로 넣어, SQS로 나가는 요청(큐, 본문, 그룹 ID, 중복 제거 ID)을 그대로 확인한다.
 */
class ShipmentServiceTest {

    private static final String QUEUE = "dev-inventory-commands.fifo";
    private static final SimulatorProperties PROPERTIES = new SimulatorProperties(QUEUE,
            new SimulatorProperties.Preset(List.of(1L, 2L, 3L), List.of(
                    new SimulatorProperties.Item("P016", 20, LocalDate.of(2026, 10, 20)),
                    new SimulatorProperties.Item("P012", 10, LocalDate.of(2026, 10, 9)))));
    // 2026-10-17 05:00:12.345 KST
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T20:00:12.345Z"), MessageJson.KST);

    private SqsAsyncClient sqs;
    private ShipmentService service;

    @BeforeEach
    void setUp() {
        sqs = mock(SqsAsyncClient.class);
        when(sqs.getQueueUrl(any(GetQueueUrlRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetQueueUrlResponse.builder().queueUrl("https://sqs.test/" + QUEUE).build()));
        GetQueueAttributesResponse noAttributes = GetQueueAttributesResponse.builder().build();
        when(sqs.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(noAttributes));
        when(sqs.getQueueAttributes(any(Consumer.class)))
                .thenReturn(CompletableFuture.completedFuture(noAttributes));
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(CompletableFuture.completedFuture(
                SendMessageResponse.builder().messageId(UUID.randomUUID().toString()).build()));
        service = new ShipmentService(new InboundPublisher(SqsTemplate.newTemplate(sqs), PROPERTIES), PROPERTIES, CLOCK);
    }

    @Test
    @DisplayName("본문 없이 부르면 프리셋대로 점포 3곳에 INBOUND 쪽지를 하나씩 보낸다")
    void presetToThreeStores() {
        ShipmentResponse response = service.ship(null);

        List<SendMessageRequest> requests = sent(3);
        assertThat(requests).extracting(SendMessageRequest::queueUrl).containsOnly("https://sqs.test/" + QUEUE);
        assertThat(requests).extracting(SendMessageRequest::messageGroupId).containsExactly("1", "2", "3");

        InboundMessage first = (InboundMessage) MessageJson.fromJson(requests.get(0).messageBody());
        assertThat(requests.get(0).messageDeduplicationId()).isEqualTo(first.messageId());
        assertThat(first.storeId()).isEqualTo(1L);
        assertThat(first.shipmentId()).isEqualTo("SHP-20261017-050012345-S1");
        assertThat(first.items()).hasSize(2);
        assertThat(first.items().get(0).productCode()).isEqualTo("P016");
        assertThat(first.items().get(0).quantity()).isEqualTo(20);
        assertThat(first.items().get(0).expiryDate())
                .isEqualTo(OffsetDateTime.parse("2026-10-20T23:59:59+09:00"));
        assertThat(first.items().get(1).productCode()).isEqualTo("P012");
        assertThat(first.items().get(1).expiryDate())
                .isEqualTo(OffsetDateTime.parse("2026-10-09T23:59:59+09:00"));

        assertThat(response.queue()).isEqualTo(QUEUE);
        assertThat(response.shipments()).extracting(ShipmentResponse.Sent::storeId).containsExactly(1L, 2L, 3L);
        assertThat(response.shipments()).extracting(ShipmentResponse.Sent::shipmentId).doesNotHaveDuplicates();
        assertThat(response.shipments().get(0).messageId()).isEqualTo(first.messageId());
    }

    @Test
    @DisplayName("본문은 type INBOUND인 쪽지 JSON 그대로다")
    void bodyIsInboundJson() {
        service.ship(null);

        String body = sent(3).get(0).messageBody();
        assertThat(body).contains("\"type\":\"INBOUND\"").contains("\"expiryDate\":\"2026-10-20T23:59:59+09:00\"");
        assertThat(MessageJson.fromJson(body)).isInstanceOf(BaseMessage.class);
    }

    @Test
    @DisplayName("요청에 점포·품목이 있으면 그 값으로 보내고, 같은 점포는 한 번만")
    void customRequest() {
        service.ship(new ShipmentRequest(List.of(2L, 2L),
                List.of(new ShipmentRequest.Item("P001", 5, LocalDate.of(2026, 11, 1)))));

        SendMessageRequest request = sent(1).get(0);
        InboundMessage message = (InboundMessage) MessageJson.fromJson(request.messageBody());
        assertThat(request.messageGroupId()).isEqualTo("2");
        assertThat(message.items()).singleElement().satisfies(item -> {
            assertThat(item.productCode()).isEqualTo("P001");
            assertThat(item.quantity()).isEqualTo(5);
        });
    }

    private List<SendMessageRequest> sent(int expected) {
        ArgumentCaptor<SendMessageRequest> captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs, times(expected)).sendMessage(captor.capture());
        return captor.getAllValues();
    }
}

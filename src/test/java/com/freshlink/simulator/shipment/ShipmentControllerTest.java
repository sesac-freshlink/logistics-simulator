package com.freshlink.simulator.shipment;

import com.freshlink.common.message.command.InboundMessage;
import com.freshlink.simulator.messaging.InboundPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShipmentControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    InboundPublisher publisher;

    @Test
    @DisplayName("본문 없이 POST하면 202와 점포 3곳의 shipmentId를 돌려준다")
    void presetShipment() throws Exception {
        when(publisher.queue()).thenReturn("dev-inventory-commands.fifo");

        mockMvc.perform(post("/simulator/shipments"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.queue").value("dev-inventory-commands.fifo"))
                .andExpect(jsonPath("$.shipments.length()").value(3))
                .andExpect(jsonPath("$.shipments[0].storeId").value(1))
                .andExpect(jsonPath("$.shipments[0].itemCount").value(2));
        verify(publisher, times(3)).publish(any(InboundMessage.class));
    }

    @Test
    @DisplayName("수량이 0이면 400이고 아무것도 보내지 않는다")
    void invalidQuantity() throws Exception {
        mockMvc.perform(post("/simulator/shipments").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeIds":[1],"items":[{"productCode":"P016","quantity":0,"expiryDate":"2026-10-20"}]}
                                """))
                .andExpect(status().isBadRequest());
        verify(publisher, never()).publish(any(InboundMessage.class));
    }

    @Test
    @DisplayName("프리셋을 조회할 수 있다")
    void preset() throws Exception {
        mockMvc.perform(get("/simulator/preset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queue").value("dev-inventory-commands.fifo"))
                .andExpect(jsonPath("$.preset.storeIds.length()").value(3))
                .andExpect(jsonPath("$.preset.items[0].productCode").value("P016"));
    }

    @Test
    @DisplayName("버튼 화면이 열린다")
    void indexPage() throws Exception {
        mockMvc.perform(get("/index.html")).andExpect(status().isOk());
    }
}

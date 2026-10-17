package com.freshlink.simulator.config;

import com.freshlink.common.message.MessageJson;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** shipmentId 날짜는 한국 시간 기준 */
    @Bean
    Clock clock() {
        return Clock.system(MessageJson.KST);
    }
}

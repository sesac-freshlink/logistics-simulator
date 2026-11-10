package com.freshlink.simulator.schedule;

import com.freshlink.simulator.config.ScheduleProperties;
import com.freshlink.simulator.messaging.InboundPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

/** simulator.schedule.enabled=true일 때만 매일 시나리오를 켠다. 꺼 두면 손 입고(POST /simulator/shipments)만 있다 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "simulator.schedule", name = "enabled", havingValue = "true")
public class DemoScheduleConfig {

    @Bean
    ShopClient shopClient(ScheduleProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new ShopClient(RestClient.builder().requestFactory(factory), props.services());
    }

    @Bean
    DemoScheduler demoScheduler(ScheduleProperties props, InboundPublisher publisher, ShopClient shop, Clock clock) {
        return new DemoScheduler(props, publisher, shop, clock);
    }

    @Bean
    DemoScheduleRunner demoScheduleRunner(DemoScheduler scheduler, ScheduleProperties props, Clock clock) {
        return new DemoScheduleRunner(scheduler, props, clock);
    }
}

package com.freshlink.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LogisticsSimulatorApplication {

	public static void main(String[] args) {
		SpringApplication.run(LogisticsSimulatorApplication.class, args);
	}

}

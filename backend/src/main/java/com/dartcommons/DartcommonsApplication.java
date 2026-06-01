package com.dartcommons;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DartcommonsApplication {

	public static void main(String[] args) {
		SpringApplication.run(DartcommonsApplication.class, args);
	}

}

package com.dartcommons;

import org.springframework.boot.SpringApplication;

public class TestDartcommonsApplication {

	public static void main(String[] args) {
		SpringApplication.from(DartcommonsApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

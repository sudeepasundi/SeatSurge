package com.seatsurge;

import org.springframework.boot.SpringApplication;

public class TestSeatSurgeApplication {

	public static void main(String[] args) {
		SpringApplication.from(SeatSurgeApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

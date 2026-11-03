package com.achpayments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

@SpringBootApplication
public class AchPaymentsApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(AchPaymentsApplication.class, args);
    }
}

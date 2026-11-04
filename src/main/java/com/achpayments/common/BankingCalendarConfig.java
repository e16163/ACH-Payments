package com.achpayments.common;

import java.time.LocalDate;
import java.util.HashSet;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class BankingCalendarConfig {

    /** Loads the holidays once at startup (Flyway has already run, since JdbcTemplate depends on it). */
    @Bean
    BankingCalendar bankingCalendar(JdbcTemplate jdbc) {
        return new BankingCalendar(new HashSet<>(
                jdbc.queryForList("SELECT holidayDt FROM BankingCalendar", LocalDate.class)));
    }
}

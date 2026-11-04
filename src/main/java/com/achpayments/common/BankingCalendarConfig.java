package com.achpayments.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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

    /** "Today" for banking dates is the Federal Reserve's day (Eastern time), not the server's. */
    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("America/New_York"));
    }
}

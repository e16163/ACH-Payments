package com.achpayments.transfer;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.achpayments.nacha.AchFileValidator;

/** UC-ACH-ORG-01 step 3 and EX-01: 9 digits, ABA 3-7-1 check digit, then the ABADirectory lookup. */
@Service
public class RoutingService {

    public static final String INVALID = "Invalid routing number. Please check the 9-digit ABA number.";

    private final JdbcTemplate jdbc;

    public RoutingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The bank name, or empty when the number is malformed, fails the check digit, or isn't in the directory. */
    public Optional<String> bankName(String routingNo) {
        if (!AchFileValidator.isValidRouting(routingNo)) {
            return Optional.empty();
        }
        return jdbc.queryForList("SELECT bankName FROM ABADirectory WHERE routingNo = ?", String.class, routingNo)
                .stream().findFirst();
    }
}

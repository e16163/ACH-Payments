package com.achpayments.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API for the admin ACH Receiving File page: read-only, until Ba's ACH Receiving Engine use case. Only ROLE_SYS_ADMIN
 * gets here; see SecurityConfig. The counts come from the file's entries, because nothing fills ACHRecvFile's totals yet.
 */
@RestController
public class ReceivingFileController {

    public record FileRow(String fileID, String fileName, LocalDate fileHeaderDt, LocalTime fileHeaderTm, String fedStatus,
            int entryCount, BigDecimal totalCreditAmt, BigDecimal totalDebitAmt) {
    }

    private final JdbcTemplate jdbc;
    private final String recv;

    public ReceivingFileController(JdbcTemplate jdbc, @Value("${app.db.receiving}") String recv) {
        this.jdbc = jdbc;
        this.recv = recv;
    }

    @GetMapping("/api/receiving/files")
    public List<FileRow> files() {
        return jdbc.query("SELECT f.fileID, f.fileName, f.fileHeaderDt, f.fileHeaderTm, f.fedStatus, COUNT(t.transferSerNo), "
                + "COALESCE(SUM(CASE WHEN t.creditFlag = 1 THEN t.amount END), 0), "
                + "COALESCE(SUM(CASE WHEN t.creditFlag = 0 THEN t.amount END), 0) "
                + "FROM " + recv + ".ACHRecvFile f LEFT JOIN " + recv + ".ACHRecvTransaction t ON t.fileID = f.fileID "
                + "GROUP BY f.fileID, f.fileName, f.fileHeaderDt, f.fileHeaderTm, f.fedStatus "
                + "ORDER BY f.fileHeaderDt DESC, f.fileID DESC",
                (rs, i) -> new FileRow(rs.getString(1), rs.getString(2), rs.getObject(3, LocalDate.class),
                        rs.getObject(4, LocalTime.class), rs.getString(5), rs.getInt(6), rs.getBigDecimal(7), rs.getBigDecimal(8)));
    }
}

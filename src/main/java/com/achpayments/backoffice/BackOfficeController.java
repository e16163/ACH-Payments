package com.achpayments.backoffice;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only rows for the back-office screens (docs/USE-CASES.md UC-ACH-BO). Guessed from the table design until their use
 * cases arrive. Who gets each side is set in SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/ach/back-office")
public class BackOfficeController {

    private static final int MAX_ROWS = 100;

    private final JdbcTemplate jdbc;
    private final Map<String, String> queries;

    public BackOfficeController(JdbcTemplate jdbc, @Value("${app.db.receiving}") String recv, @Value("${app.db.origination}") String orig) {
        this.jdbc = jdbc;
        this.queries = Map.ofEntries(
                Map.entry("origination/returns", "SELECT * FROM " + orig + ".ACHOrigReturn ORDER BY returnDt DESC, returnID DESC"),
                Map.entry("origination/return-actions", "SELECT r.returnID, r.processID, a.actionName, r.actionTm, r.lastModBy FROM " + orig
                        + ".ACHOrigReturnAction r JOIN " + orig + ".Action a ON a.actionID = r.actionID ORDER BY r.actionTm DESC"),
                Map.entry("origination/reversals", fileActions(orig, "ACHOrigReversalAction")),
                Map.entry("origination/resubmissions", fileActions(orig, "ACHOrigResubAction")),
                Map.entry("origination/nocs", "SELECT * FROM " + orig + ".ACHOrigNOC ORDER BY createTm DESC, nocID DESC"),
                Map.entry("origination/noc-actions", "SELECT n.nocID, n.processID, a.actionName, n.actionTm, n.lastModBy FROM " + orig
                        + ".ACHOrigNOCAction n JOIN " + orig + ".Action a ON a.actionID = n.actionID ORDER BY n.actionTm DESC"),
                Map.entry("origination/account-subs", "SELECT * FROM " + orig + ".ACHOrigAcctSubs ORDER BY acctSubsID DESC"),
                Map.entry("origination/trancode-subs", "SELECT * FROM " + orig + ".ACHOrigTransCodeSubs ORDER BY nocID DESC"),
                Map.entry("receiving/returns", "SELECT * FROM " + recv + ".ACHRecvReturn ORDER BY returnDt DESC, returnID DESC"),
                Map.entry("receiving/return-requests", "SELECT * FROM " + recv + ".ACHRecvReturnRequest ORDER BY dueDt, requestID"),
                // No reversal table on this side: Nacha marks a reversal with REVERSAL in the Company Entry Description.
                Map.entry("receiving/reversals", "SELECT * FROM " + recv + ".ACHRecvTransaction WHERE companyEntryDesc = 'REVERSAL' "
                        + "ORDER BY effectiveDt DESC, tranID DESC"),
                Map.entry("receiving/nocs", "SELECT * FROM " + recv + ".ACHRecvNOC ORDER BY createTm DESC, nocID DESC"),
                Map.entry("receiving/account-subs", "SELECT * FROM " + recv + ".ACHRecvAcctSubs ORDER BY acctSubsID DESC"),
                Map.entry("receiving/trancode-subs", "SELECT * FROM " + recv + ".ACHRecvTransCodeSubs ORDER BY nocID DESC"));
    }

    private static String fileActions(String orig, String table) {
        return "SELECT x.fileID, x.processID, a.actionName, x.actionTm, x.lastModBy, f.fileHeaderDt, f.fedStatus, f.totalDebitAmt, f.totalCreditAmt "
                + "FROM " + orig + "." + table + " x JOIN " + orig + ".Action a ON a.actionID = x.actionID JOIN " + orig
                + ".ACHOrigFile f ON f.fileID = x.fileID ORDER BY x.actionTm DESC";
    }

    @GetMapping("/{side}/{dataset}")
    public List<Map<String, Object>> rows(@PathVariable String side, @PathVariable String dataset) {
        String sql = queries.get(side + "/" + dataset);
        if (sql == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(sql + " LIMIT " + MAX_ROWS);
        // A java.sql.Date would reach the page as a UTC timestamp, a day off in the west
        rows.forEach(row -> row.replaceAll((column, value) -> value instanceof java.sql.Date d ? d.toLocalDate() : value));
        return rows;
    }
}

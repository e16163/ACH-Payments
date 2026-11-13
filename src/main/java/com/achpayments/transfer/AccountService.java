package com.achpayments.transfer;

import static com.achpayments.common.AppLogRepository.ACH_ORIG;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.achpayments.common.AppLogRepository;
import com.achpayments.memo.AccountHistory;
import com.achpayments.transfer.TransferRejected.Kind;

/**
 * Ba (2026-11-13): the member's account history (SimCoreAcctTran) and internal transfers between the member's own
 * accounts. An internal transfer is a book transfer inside the core, so it moves the available and posted balances of
 * both accounts at once.
 */
@Service
public class AccountService {

    public record AcctTran(long acctTranId, LocalDateTime tranTm, String tranDesc, boolean credit, BigDecimal amount,
            String balanceType, String tranSource, String tranRef, BigDecimal availBalAfter, BigDecimal ledgerBalAfter) {
    }

    public record AcctTranPage(List<AcctTran> items, int total, int page, int limit) {
    }

    public record InternalTransferRequest(String fromAcctNo, String toAcctNo, BigDecimal amount, String memo) {
    }

    public record InternalTransferReceipt(String transferId, String fromAcctNo, String toAcctNo, BigDecimal amount,
            BigDecimal fromAvailBal, BigDecimal toAvailBal) {
    }

    private final JdbcTemplate jdbc;
    private final TransferService transfers;
    private final AccountHistory history;
    private final AppLogRepository appLog;

    public AccountService(JdbcTemplate jdbc, TransferService transfers, AccountHistory history, AppLogRepository appLog) {
        this.jdbc = jdbc;
        this.transfers = transfers;
        this.history = history;
        this.appLog = appLog;
    }

    /** The account's credits and debits, newest first; empty when the account isn't the member's. */
    public Optional<AcctTranPage> transactions(String memberNo, String acctNo, int page, int limit) {
        Integer own = jdbc.queryForObject("SELECT COUNT(*) FROM SimCoreAccount WHERE acctNo = ? AND memberNo = ?", Integer.class,
                acctNo, memberNo);
        if (own == null || own == 0) {
            return Optional.empty();
        }
        int size = Math.max(1, Math.min(limit, 100)), from = (Math.max(page, 1) - 1) * size;
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM SimCoreAcctTran WHERE acctNo = ?", Integer.class, acctNo);
        List<AcctTran> items = jdbc.query("SELECT acctTranID, tranTm, tranDesc, creditFlag, amount, balanceType, tranSource, tranRef, "
                + "availBalAfter, ledgerBalAfter FROM SimCoreAcctTran WHERE acctNo = ? ORDER BY acctTranID DESC LIMIT ? OFFSET ?",
                (rs, i) -> new AcctTran(rs.getLong(1), rs.getObject(2, LocalDateTime.class), rs.getString(3), rs.getBoolean(4),
                        rs.getBigDecimal(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getBigDecimal(9), rs.getBigDecimal(10)),
                acctNo, size, from);
        return Optional.of(new AcctTranPage(items, total == null ? 0 : total, Math.max(page, 1), size));
    }

    @Transactional
    public InternalTransferReceipt internalTransfer(String memberNo, InternalTransferRequest r) {
        if (r == null) {
            throw invalid("Fill in the transfer details.");
        }
        List<TransferService.Account> mine = transfers.accounts(memberNo); // active accounts only
        TransferService.Account from = mine.stream().filter(a -> a.acctNo().equals(r.fromAcctNo())).findFirst()
                .orElseThrow(() -> invalid("Choose one of your accounts to transfer from."));
        TransferService.Account to = mine.stream().filter(a -> a.acctNo().equals(r.toAcctNo())).findFirst()
                .orElseThrow(() -> invalid("Choose one of your accounts to transfer to."));
        if (from.acctNo().equals(to.acctNo())) {
            throw invalid("Choose two different accounts.");
        }
        BigDecimal amount = r.amount();
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2
                || amount.compareTo(TransferService.MAX_AMOUNT) > 0) {
            throw invalid("Enter an amount between $0.01 and $99,999,999,999.99.");
        }
        amount = amount.setScale(2);
        String memo = r.memo() == null ? "" : r.memo().trim();
        if (!memo.matches("[A-Za-z0-9 .,&'-]{0,30}")) {
            throw invalid("The memo can be up to 30 letters, numbers and spaces.");
        }

        // One guarded UPDATE, so two transfers at once can't both spend the same money
        int debited = jdbc.update("UPDATE SimCoreAccount SET availBal = availBal - ?, ledgerBal = ledgerBal - ? "
                + "WHERE acctNo = ? AND memberNo = ? AND acctStatus = 'ACTIVE' AND availBal >= ?", amount, amount, from.acctNo(), memberNo, amount);
        if (debited != 1) {
            throw new TransferRejected(Kind.FUNDS, "Your available balance isn't enough for this transfer.");
        }
        jdbc.update("UPDATE SimCoreAccount SET availBal = availBal + ?, ledgerBal = ledgerBal + ? WHERE acctNo = ? AND memberNo = ?",
                amount, amount, to.acctNo(), memberNo);

        String id = "INT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        String note = memo.isEmpty() ? "" : " - " + memo;
        history.record(from.acctNo(), "Transfer to " + label(to) + note, false, amount, AccountHistory.POSTED, "INTERNAL", id);
        history.record(to.acctNo(), "Transfer from " + label(from) + note, true, amount, AccountHistory.POSTED, "INTERNAL", id);
        appLog.log(ACH_ORIG, "INFO", "Member %s made internal transfer %s of %s".formatted(memberNo, id, amount));
        return new InternalTransferReceipt(id, from.acctNo(), to.acctNo(), amount, available(from.acctNo()), available(to.acctNo()));
    }

    private BigDecimal available(String acctNo) {
        return jdbc.queryForObject("SELECT availBal FROM SimCoreAccount WHERE acctNo = ?", BigDecimal.class, acctNo);
    }

    private static String label(TransferService.Account a) {
        String name = a.acctNickname() != null ? a.acctNickname() : a.acctName();
        return name + " ••" + a.acctNo().substring(Math.max(0, a.acctNo().length() - 4));
    }

    private static TransferRejected invalid(String message) {
        return new TransferRejected(Kind.INVALID, message);
    }
}

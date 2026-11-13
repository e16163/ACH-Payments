package com.achpayments.transfer;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.achpayments.common.AppLogRepository;
import com.achpayments.common.BankingCalendar;
import com.achpayments.common.MemberNumbers;

import jakarta.servlet.http.HttpServletRequest;

/**
 * UC-ACH-ORG-01 (External ACH Transfer) API for the member screens. Only members who passed MFA get here
 * (SecurityConfig). The member number always comes from the login, never from the request body.
 */
@RestController
public class TransferController {

    /**
     * Feeds the date picker: the earliest allowed date, the holidays to disable (weekends are disabled too), and the
     * same-day cutoff (HH:mm Eastern) before which today is allowed.
     */
    public record DateRules(LocalDate earliest, List<LocalDate> holidays, java.time.LocalTime sameDayCutoff) {
    }

    private final TransferDates dates;
    private final BankingCalendar calendar;
    private final TransferService transfers;
    private final AccountService accountService;
    private final AppLogRepository appLog;
    private final MemberNumbers memberNumbers;

    public TransferController(TransferDates dates, BankingCalendar calendar, TransferService transfers, AccountService accountService,
            AppLogRepository appLog, MemberNumbers memberNumbers) {
        this.dates = dates;
        this.calendar = calendar;
        this.transfers = transfers;
        this.accountService = accountService;
        this.appLog = appLog;
        this.memberNumbers = memberNumbers;
    }

    private String memberNo(Principal user) {
        return memberNumbers.of(user).orElseThrow(() -> new AccessDeniedException("No member number for " + user.getName()));
    }

    @GetMapping("/api/transfers/dates")
    public DateRules dateRules() {
        return new DateRules(dates.earliest(), calendar.holidays().stream().sorted().toList(), dates.cutoff());
    }

    @GetMapping("/api/member/accounts")
    public List<TransferService.Account> accounts(Principal member) {
        return transfers.accounts(memberNo(member));
    }

    /** Ba (2026-11-13): the account's credits and debits, newest first. Another member's account is a 404. */
    @GetMapping("/api/member/accounts/{acctNo}/transactions")
    public ResponseEntity<AccountService.AcctTranPage> accountTransactions(@PathVariable String acctNo,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int limit, Principal member) {
        return ResponseEntity.of(accountService.transactions(memberNo(member), acctNo, page, limit));
    }

    /** Ba (2026-11-13): a transfer between the member's own accounts, posted at once. */
    @PostMapping("/api/transfers/internal")
    public ResponseEntity<AccountService.InternalTransferReceipt> internal(@RequestBody AccountService.InternalTransferRequest request,
            Principal member) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.internalTransfer(memberNo(member), request));
    }

    @PostMapping("/api/transfers/preview")
    public TransferService.Preview preview(@RequestBody TransferRequest request, Principal member) {
        return transfers.preview(memberNo(member), request);
    }

    @PostMapping("/api/transfers")
    public ResponseEntity<TransferService.Receipt> submit(@RequestBody TransferRequest request, Principal member) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transfers.submit(memberNo(member), request));
    }

    @GetMapping("/api/transfers/{transferSerNo}")
    public ResponseEntity<TransferService.Receipt> receipt(@PathVariable String transferSerNo, Principal member) {
        return ResponseEntity.of(transfers.receipt(memberNo(member), transferSerNo));
    }

    @ExceptionHandler(TransferRejected.class)
    public ResponseEntity<Map<String, String>> rejected(TransferRejected e, Principal member, HttpServletRequest request) {
        // The kind only: the messages can hold the member's balance
        appLog.log(AppLogRepository.ACH_ORIG, "WARN", "Transfer refused for user '%s' on %s: %s".formatted(member.getName(), request.getRequestURI(), e.kind()));
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "kind", e.kind().name()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Some details aren't valid. Check the form and try again.",
                "kind", TransferRejected.Kind.INVALID.name()));
    }
}

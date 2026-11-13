package com.achpayments.transfer;

import java.security.Principal;
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
import org.springframework.web.bind.annotation.RestController;

import com.achpayments.common.MemberNumbers;

/**
 * External Bank Accounts, as Ba's ach-origination-system.yaml defines them. Only members who passed MFA get here
 * (SecurityConfig), and {@code holderId} must be the signed-in member's own number.
 */
@RestController
public class ExternalAccountController {

    private final TransferService transfers;
    private final MemberNumbers memberNumbers;

    public ExternalAccountController(TransferService transfers, MemberNumbers memberNumbers) {
        this.transfers = transfers;
        this.memberNumbers = memberNumbers;
    }

    /** Any holder other than the signed-in member's own number is someone else's. */
    private String ownHolder(Principal user, String holderId) {
        if (!memberNumbers.of(user).filter(holderId::equals).isPresent()) {
            throw new AccessDeniedException("Holder " + holderId + " isn't " + user.getName());
        }
        return holderId;
    }

    @GetMapping("/api/v1/holders/{holderId}/external-accounts")
    public List<TransferService.ExternalAccount> list(@PathVariable String holderId, Principal member) {
        return transfers.externalAccounts(ownHolder(member, holderId));
    }

    @PostMapping("/api/v1/holders/{holderId}/external-accounts")
    public ResponseEntity<TransferService.AddedExternalAccount> add(@PathVariable String holderId,
            @RequestBody TransferService.NewExternalAccount request, Principal member) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transfers.addExternalAccount(ownHolder(member, holderId), request));
    }

    @ExceptionHandler(TransferRejected.class)
    public ResponseEntity<Map<String, String>> rejected(TransferRejected e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable() {
        return ResponseEntity.badRequest().body(Map.of("error", "Some details aren't valid. Check the form and try again."));
    }
}

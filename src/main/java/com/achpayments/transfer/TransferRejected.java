package com.achpayments.transfer;

/** A transfer that can't go ahead. {@code kind} tells the UI how to show it (e.g. LIMIT is the design's EX-02 modal). */
public class TransferRejected extends RuntimeException {

    public enum Kind { INVALID, LIMIT, NOT_VERIFIED, FUNDS }

    private final Kind kind;

    public TransferRejected(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}

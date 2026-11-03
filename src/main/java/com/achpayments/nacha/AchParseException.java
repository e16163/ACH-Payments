package com.achpayments.nacha;

public class AchParseException extends RuntimeException {

    private final int lineNumber;

    public AchParseException(int lineNumber, String message) {
        super("Line " + lineNumber + ": " + message);
        this.lineNumber = lineNumber;
    }

    public int lineNumber() {
        return lineNumber;
    }
}

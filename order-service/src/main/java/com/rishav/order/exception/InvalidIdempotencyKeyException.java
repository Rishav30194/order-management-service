package com.rishav.order.exception;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException(int maxLength) {
        super("Idempotency-Key must be 1 to " + maxLength + " characters and not blank");
    }
}

package com.rishav.order.exception;

public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(Long orderId) {
        super("Idempotency-Key was already used for order " + orderId + " with a different request");
    }
}

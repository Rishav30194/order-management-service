package com.rishav.order.exception;

public class DownstreamUnavailableException extends RuntimeException {

    public DownstreamUnavailableException(String service, Throwable cause) {
        super(service + " is unavailable", cause);
    }
}

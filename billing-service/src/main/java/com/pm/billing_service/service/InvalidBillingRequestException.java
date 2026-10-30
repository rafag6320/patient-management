package com.pm.billing_service.service;

public class InvalidBillingRequestException extends RuntimeException {
    public InvalidBillingRequestException() { super("Invalid billing account request"); }
}

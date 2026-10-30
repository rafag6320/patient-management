package com.pm.patient_service.grpc;

/** Safe classification: never retains remote exception details. */
public final class BillingCallException extends RuntimeException {
    public enum Kind { RETRYABLE, UNCERTAIN, REVIEW }
    private final Kind kind;
    public BillingCallException(Kind kind) {
        super(kind == Kind.REVIEW ? "Billing requires review; registration may exist"
                : "Registration pending; retry with the same Idempotency-Key");
        this.kind = kind;
    }
    public Kind kind() { return kind; }
    public int httpStatus() { return kind == Kind.REVIEW ? 502 : 503; }
}

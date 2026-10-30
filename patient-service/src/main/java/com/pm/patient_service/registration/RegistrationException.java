package com.pm.patient_service.registration;
public final class RegistrationException extends RuntimeException {
    private final int status;
    public RegistrationException(int status, String message) { super(message); this.status=status; }
    public int status() { return status; }
}

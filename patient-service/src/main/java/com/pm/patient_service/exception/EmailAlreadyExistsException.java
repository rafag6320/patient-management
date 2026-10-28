package com.pm.patient_service.exception;


public class EmailAlreadyExistsException extends RuntimeException {

    public EmailAlreadyExistsException(String string) {
        super(string);
    }
    
}

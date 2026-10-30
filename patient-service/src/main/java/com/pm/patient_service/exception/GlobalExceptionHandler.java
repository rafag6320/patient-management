package com.pm.patient_service.exception;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(com.pm.patient_service.registration.RegistrationException.class)
    public ResponseEntity<Map<String,String>> registrationFailure(com.pm.patient_service.registration.RegistrationException ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("error",ex.getMessage()));
    }

    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class})
    public ResponseEntity<Map<String,String>> storageFailure(RuntimeException ex) {
        return ResponseEntity.status(503).body(Map.of("error","Storage unavailable; registration may exist. Retry with the same Idempotency-Key"));
    }

    @ExceptionHandler(com.pm.patient_service.grpc.BillingCallException.class)
    public ResponseEntity<Map<String,String>> billingFailure(com.pm.patient_service.grpc.BillingCallException ex) {
        return ResponseEntity.status(ex.httpStatus()).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationExceptions(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(
            error-> errors.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(errors);
    }

    @ExceptionHandler({EmailAlreadyExistsException.class, PatientAlreadyExistsException.class})
    public ResponseEntity<Map<String, String>> handleEmailAlreadyExistsException(
        RuntimeException ex) {
         
        logger.warn("Patient uniqueness conflict");
        Map<String, String> error = new HashMap<>();
        error.put("error", ex.getMessage());
        return ResponseEntity.badRequest().body(error);
    }

    @ExceptionHandler(PatientNotFoundException.class)
    public ResponseEntity<Map<String, String>> handlePatientNotFoundException(
        PatientNotFoundException ex) {
         
        logger.warn("PatientNotFoundException: {}", ex.getMessage());
        Map<String, String> error = new HashMap<>();
        error.put("error", ex.getMessage());
        return ResponseEntity.status(404).body(error);
    }
}

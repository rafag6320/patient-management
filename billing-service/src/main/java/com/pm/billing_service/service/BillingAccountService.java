package com.pm.billing_service.service;

import com.pm.billing_service.entity.BillingAccount;
import com.pm.billing_service.repository.BillingAccountRepository;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class BillingAccountService {
    private final BillingAccountRepository accounts;
    private final Validator validator;

    public BillingAccountService(BillingAccountRepository accounts,Validator validator) {
        this.accounts=accounts; this.validator=validator;
    }
    private record Input(@NotBlank @Size(max=255) String patientId,
                         @NotBlank @Size(max=100) String name,
                         @NotBlank @Email @Size(max=255) String email) { }

    public BillingAccount getOrCreate(String patientId,String name,String email) {
        if (!validator.validate(new Input(patientId,name,email)).isEmpty()) {
            throw new InvalidBillingRequestException();
        }
        var existing=accounts.findByPatientId(patientId);
        if (existing.isPresent()) { return existing.get(); }
        try {
            // Repository transaction commits before returning. No remote effects or shared DB access.
            return accounts.saveAndFlush(new BillingAccount(UUID.randomUUID(),patientId,name,email));
        } catch (DataIntegrityViolationException failure) {
            for (Throwable cause=failure;cause!=null;cause=cause.getCause()) {
                if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                    // The competing insert has committed. Read it in a separate repository transaction.
                    var winner=accounts.findByPatientId(patientId);
                    if (winner.isPresent()) { return winner.get(); }
                    break;
                }
            }
            throw failure;
        }
    }
}

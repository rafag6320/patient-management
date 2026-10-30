package com.pm.billing_service.repository;

import com.pm.billing_service.entity.BillingAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingAccountRepository extends JpaRepository<BillingAccount,UUID> {
    Optional<BillingAccount> findByPatientId(String patientId);
}

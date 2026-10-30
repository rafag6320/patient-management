package com.pm.patient_service.registration;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

/** Mapping for schema validation; mutations use conditional SQL in RegistrationStore. */
@Entity @Table(name="registration_work")
class RegistrationWorkEntity {
    protected RegistrationWorkEntity() {}
    @Column(name="recovery_count",nullable=false)
    private Integer recoveryCount;
    @Id
    @Column(name="patient_id",nullable=false)
    private UUID patientId;
    @Column(name="key_hash",nullable=true,length=64)
    private String keyHash;
    @Column(name="request_hash",nullable=false,length=64)
    private String requestHash;
    @Column(name="name",nullable=false,length=100)
    private String name;
    @Column(name="email",nullable=false,length=255)
    private String email;
    @Column(name="address",nullable=false,length=200)
    private String address;
    @Column(name="birth_date",nullable=false)
    private LocalDate birthDate;
    @Column(name="registered_date",nullable=false)
    private LocalDate registeredDate;
    @Column(name="dni",nullable=false)
    private Integer dni;
    @Column(name="billing_status",nullable=false,length=20)
    private String billingStatus;
    @Column(name="account_id",nullable=true)
    private UUID accountId;
    @Column(name="event_status",nullable=false,length=20)
    private String eventStatus;
    @Column(name="attempts",nullable=false)
    private Integer attempts;
    @Column(name="next_attempt_at",nullable=false)
    private Instant nextAttemptAt;
    @Column(name="lease_token",nullable=true)
    private UUID leaseToken;
    @Column(name="lease_until",nullable=false)
    private Instant leaseUntil;
    @Column(name="deleted",nullable=false)
    private Boolean deleted;
    @Column(name="correlation_id",nullable=true,length=64)
    private String correlationId;
    @Column(name="last_error",nullable=true,length=64)
    private String lastError;
    @Column(name="created_at",nullable=false)
    private Instant createdAt;
}

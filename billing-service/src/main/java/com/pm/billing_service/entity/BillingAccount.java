package com.pm.billing_service.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name="billing_account")
public class BillingAccount {
    @Id
    private UUID id;
    @Column(name="patient_id",nullable=false,unique=true,length=255)
    private String patientId;
    @Column(nullable=false,length=100)
    private String name;
    @Column(nullable=false,length=255)
    private String email;

    protected BillingAccount() { }
    public BillingAccount(UUID id,String patientId,String name,String email) {
        this.id=id; this.patientId=patientId; this.name=name; this.email=email;
    }
    public UUID getId() { return id; }
    public String getPatientId() { return patientId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
}

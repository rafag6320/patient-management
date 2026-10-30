package com.pm.patient_service.dto.validators;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = PatientDateValidator.class)
public @interface ValidPatientDate {
    String message() default "Date must be a valid date in yyyy-MM-dd format";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

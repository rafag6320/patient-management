package com.pm.patient_service.dto.validators;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = DniValidator.class)
public @interface ValidDni {
    String message() default "DNI must contain digits and be between 0 and 2147483647";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

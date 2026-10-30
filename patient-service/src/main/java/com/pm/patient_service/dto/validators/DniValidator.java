package com.pm.patient_service.dto.validators;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class DniValidator implements ConstraintValidator<ValidDni, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) { return true; }
        if (!value.matches("[0-9]{1,10}")) { return false; }
        try { Integer.parseInt(value); return true; }
        catch (NumberFormatException e) { return false; }
    }
}

package com.pm.patient_service.dto.validators;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public class PatientDateValidator implements ConstraintValidator<ValidPatientDate, String> {
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // La obligatoriedad depende del grupo de validación del DTO.
        if (value == null || value.isBlank()) { return true; }
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) { return false; }
        try { LocalDate.parse(value); return true; }
        catch (DateTimeParseException e) { return false; }
    }
}

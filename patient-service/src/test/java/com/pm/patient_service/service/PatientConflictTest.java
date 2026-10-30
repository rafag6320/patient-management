package com.pm.patient_service.service;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import com.pm.patient_service.dto.PatientRequestDTO;
import com.pm.patient_service.entity.Patient;
import com.pm.patient_service.exception.PatientAlreadyExistsException;
import com.pm.patient_service.repository.PatientRepository;
import com.pm.patient_service.grpc.BillingServiceGrpcClient;
import com.pm.patient_service.kafka.KafkaProducer;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PatientConflictTest {
    @ParameterizedTest
    @ValueSource(booleans={true})
    void catchesDatabaseRaceEvenWhenPrechecksPassed(boolean update) {
        checkFailure(update,"23505",true);
    }

    @ParameterizedTest
    @ValueSource(booleans={true})
    void doesNotMaskOtherIntegrityFailuresAsDuplicates(boolean update) {
        checkFailure(update,"23502",false);
    }

    private void checkFailure(boolean update,String sqlState,boolean duplicate) {
        var repository=mock(PatientRepository.class);
        var billing=mock(BillingServiceGrpcClient.class);
        var kafka=mock(KafkaProducer.class);
        var registrations=mock(com.pm.patient_service.registration.RegistrationCoordinator.class);
        var store=mock(com.pm.patient_service.registration.RegistrationStore.class);
        when(store.mutate(any(),eq(false),any())).thenAnswer(inv -> ((java.util.function.Supplier<?>)inv.getArgument(2)).get());
        var service=new PatientService(repository,registrations,store);
        UUID id=UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(new Patient(id,"Test",123,"old@example.com",
                "Address",LocalDate.of(1990,1,1),LocalDate.of(2026,1,1))));
        var failure=new DataIntegrityViolationException("test write",new SQLException("test constraint",sqlState));
        when(repository.saveAndFlush(any())).thenThrow(failure);
        var request=new PatientRequestDTO();
        request.setName("Test"); request.setEmail("new@example.com"); request.setDNI("456");
        request.setAddress("Address"); request.setDateOfBirth("1990-01-01"); request.setRegisteredDate("2026-01-01");
        org.junit.jupiter.api.function.Executable action=()-> {
            if (update) { service.updatePatient(id,request); } else { service.createPatient(request); }
        };
        if (duplicate) { assertThrows(PatientAlreadyExistsException.class,action); }
        else { assertSame(failure,assertThrows(DataIntegrityViolationException.class,action)); }
        verifyNoInteractions(billing,kafka);
    }
}

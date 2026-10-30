package com.pm.billing_service.service;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import com.pm.billing_service.entity.BillingAccount;
import com.pm.billing_service.repository.BillingAccountRepository;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BillingAccountServiceTest {
    private static ValidatorFactory validation;
    private BillingAccountRepository accounts;
    private BillingAccountService service;
    @BeforeAll static void validation() { validation=Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void close() { validation.close(); }
    @BeforeEach void setup() { accounts=mock(BillingAccountRepository.class); service=new BillingAccountService(accounts,validation.getValidator()); }
    private BillingAccount existing() { return new BillingAccount(UUID.randomUUID(),"patient","First","first@example.com"); }

    @Test void storesRealAccountAndReturnsItsId() {
        when(accounts.saveAndFlush(any())).thenAnswer(call->call.getArgument(0));
        var result=service.getOrCreate("patient","First","first@example.com");
        assertNotNull(result.getId()); assertEquals("patient",result.getPatientId());
        verify(accounts).saveAndFlush(result);
    }
    @Test void repeatedRequestNeverUpdatesStoredSnapshot() {
        var first=existing(); when(accounts.findByPatientId("patient")).thenReturn(Optional.of(first));
        assertSame(first,service.getOrCreate("patient","Different","different@example.com"));
        verify(accounts,never()).saveAndFlush(any());
    }
    @Test void databaseRaceReturnsWinnerAfterFailedInsertTransaction() {
        var winner=existing();
        when(accounts.findByPatientId("patient")).thenReturn(Optional.empty(),Optional.of(winner));
        when(accounts.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("fixture",new SQLException("fixture","23505")));
        assertSame(winner,service.getOrCreate("patient","First","first@example.com"));
    }
    @Test void unrelatedConstraintFailureDoesNotReturnSuccess() {
        var failure=new DataIntegrityViolationException("fixture",new SQLException("fixture","23502"));
        when(accounts.saveAndFlush(any())).thenThrow(failure);
        assertSame(failure,assertThrows(DataIntegrityViolationException.class,()->service.getOrCreate("patient","First","first@example.com")));
    }
    @Test void uniqueFailureWithoutMatchingPatientIsNotSilentlyAccepted() {
        var failure=new DataIntegrityViolationException("fixture",new SQLException("fixture","23505"));
        when(accounts.saveAndFlush(any())).thenThrow(failure);
        assertSame(failure,assertThrows(DataIntegrityViolationException.class,()->service.getOrCreate("patient","First","first@example.com")));
    }
    @Test void validationHappensBeforeRepositoryAccess() {
        assertThrows(InvalidBillingRequestException.class,()->service.getOrCreate(null,"First","first@example.com"));
        assertThrows(InvalidBillingRequestException.class,()->service.getOrCreate("patient"," ","first@example.com"));
        assertThrows(InvalidBillingRequestException.class,()->service.getOrCreate("patient","First","invalid"));
        verifyNoInteractions(accounts);
    }
}

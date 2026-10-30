package com.pm.billing_service.grpc;

import billing.BillingRequest;
import billing.BillingResponse;
import com.pm.billing_service.entity.BillingAccount;
import com.pm.billing_service.service.BillingAccountService;
import com.pm.billing_service.service.InvalidBillingRequestException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BillingGrpcServiceTest {
    @Test void storageAndValidationFailuresNeverEmitSuccessOrLeakDetails() {
        for (RuntimeException failure:new RuntimeException[]{new InvalidBillingRequestException(),
                new DataAccessResourceFailureException("private database details"),new RuntimeException("private database details")}) {
            var service=mock(BillingAccountService.class);
            when(service.getOrCreate(anyString(),anyString(),anyString())).thenThrow(failure);
            @SuppressWarnings("unchecked") StreamObserver<BillingResponse> observer=mock(StreamObserver.class);
            new BillingGrpcService(service).createBillingAccount(BillingRequest.getDefaultInstance(),observer);
            var error=org.mockito.ArgumentCaptor.forClass(Throwable.class);
            verify(observer).onError(error.capture()); verify(observer,never()).onNext(any()); verify(observer,never()).onCompleted();
            var status=Status.fromThrowable(error.getValue());
            assertEquals(failure instanceof InvalidBillingRequestException ? Status.Code.INVALID_ARGUMENT
                : failure instanceof DataAccessResourceFailureException ? Status.Code.UNAVAILABLE : Status.Code.INTERNAL,status.getCode());
            assertFalse(status.getDescription().contains("private database details"));
        }
    }
    @Test void respondsOnlyWithPersistedServiceResult() {
        var service=mock(BillingAccountService.class);
        var account=new BillingAccount(UUID.randomUUID(),"patient","Name","name@example.com");
        when(service.getOrCreate(anyString(),anyString(),anyString())).thenReturn(account);
        @SuppressWarnings("unchecked") StreamObserver<BillingResponse> observer=mock(StreamObserver.class);
        new BillingGrpcService(service).createBillingAccount(BillingRequest.getDefaultInstance(),observer);
        verify(observer).onNext(BillingResponse.newBuilder().setAccountId(account.getId().toString()).setSuccess(true).build());
        verify(observer).onCompleted(); verify(observer,never()).onError(any());
    }
}

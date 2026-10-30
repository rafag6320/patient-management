package com.pm.billing_service.grpc;

import com.pm.billing_service.entity.BillingAccount;
import com.pm.billing_service.service.BillingAccountService;
import com.pm.billing_service.service.InvalidBillingRequestException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import io.grpc.Status;

import billing.BillingRequest;
import billing.BillingResponse;
import billing.BillingServiceGrpc;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class BillingGrpcService extends BillingServiceGrpc.BillingServiceImplBase {
    private final BillingAccountService accounts;

    public BillingGrpcService(BillingAccountService accounts) { this.accounts=accounts; }
    
    @Override
    public void createBillingAccount(BillingRequest billingRequest,
        StreamObserver<BillingResponse> responseObserver) {
        BillingAccount account;
        try {
            account=accounts.getOrCreate(billingRequest.getPatientId(),billingRequest.getName(),billingRequest.getEmail());
        } catch (InvalidBillingRequestException ex) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid billing account request").asRuntimeException());
            return;
        } catch (DataAccessResourceFailureException | TransientDataAccessException | CannotCreateTransactionException ex) {
            responseObserver.onError(Status.UNAVAILABLE.withDescription("Billing storage unavailable").asRuntimeException());
            return;
        } catch (RuntimeException ex) {
            responseObserver.onError(Status.INTERNAL.withDescription("Billing account could not be created").asRuntimeException());
            return;
        }
        responseObserver.onNext(BillingResponse.newBuilder().setAccountId(account.getId().toString()).setSuccess(true).build());
        responseObserver.onCompleted();
        }
}

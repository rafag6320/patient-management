package com.pm.billing_service.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import billing.BillingRequest;
import billing.BillingResponse;
import billing.BillingServiceGrpc;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class BillingGrpcService extends BillingServiceGrpc.BillingServiceImplBase {
    private static final Logger log = LoggerFactory.getLogger(BillingGrpcService.class);
    
    @Override
    public void createBillingAccount(BillingRequest billingRequest,
        StreamObserver<BillingResponse> responseObserver) {
            log.info("Creating billing account for request: {}", billingRequest.toString());
        
            // Business logic to create a billing account would go here. For now, we will just return a success response.

            BillingResponse response = BillingResponse.newBuilder()
                .setAccountId("12345")
                .setSuccess(true)
                .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
        }
}

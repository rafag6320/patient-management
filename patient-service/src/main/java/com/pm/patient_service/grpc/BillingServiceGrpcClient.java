package com.pm.patient_service.grpc;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import billing.BillingRequest;
import billing.BillingResponse;
import billing.BillingServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import static com.pm.patient_service.grpc.BillingCallException.Kind.*;

@Service
public class BillingServiceGrpcClient implements AutoCloseable {
    private final ManagedChannel channel;
    private final Duration deadline;

    @Autowired
    public BillingServiceGrpcClient(@Value("${billing.service.address}") String address,
            @Value("${billing.service.port}") int port,
            @Value("${billing.service.deadline:2s}") Duration deadline) {
        this(validatedChannel(address, port, deadline), deadline);
    }

    BillingServiceGrpcClient(ManagedChannel channel, Duration deadline) {
        validateDeadline(deadline);
        this.channel = channel;
        this.deadline = deadline;
    }

    private static ManagedChannel validatedChannel(String address, int port, Duration deadline) {
        validateDeadline(deadline);
        return ManagedChannelBuilder.forAddress(address, port).usePlaintext().disableRetry().build();
    }

    private static void validateDeadline(Duration value) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("billing.service.deadline must be positive and at most 30s");
        }
    }

    public BillingResponse createBillingAccount(String patientId, String name, String email) {
        try {
            var metadata=new io.grpc.Metadata();
            String correlation=org.slf4j.MDC.get("correlationId");
            if(correlation!=null) { metadata.put(io.grpc.Metadata.Key.of("correlation-id",io.grpc.Metadata.ASCII_STRING_MARSHALLER),correlation); }
            var response = BillingServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .withDeadlineAfter(deadline.toNanos(), TimeUnit.NANOSECONDS)
                    .createBillingAccount(BillingRequest.newBuilder().setPatientId(patientId)
                            .setName(name).setEmail(email).build());
            if (!response.getSuccess()) { throw new BillingCallException(REVIEW); }
            try {
                UUID id = UUID.fromString(response.getAccountId());
                if (!id.toString().equalsIgnoreCase(response.getAccountId())) { throw new IllegalArgumentException(); }
            } catch (IllegalArgumentException ex) { throw new BillingCallException(REVIEW); }
            return response;
        } catch (StatusRuntimeException ex) {
            throw new BillingCallException(classify(ex.getStatus().getCode()));
        }
    }

    static BillingCallException.Kind classify(Status.Code code) {
        return switch (code) {
            case UNAVAILABLE, RESOURCE_EXHAUSTED -> RETRYABLE;
            case DEADLINE_EXCEEDED, CANCELLED, INTERNAL, UNKNOWN, ABORTED -> UNCERTAIN;
            default -> REVIEW;
        };
    }

    @Override @PreDestroy
    public void close() {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
                channel.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

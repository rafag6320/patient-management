package com.pm.patient_service.grpc;

import billing.*;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class BillingServiceGrpcClientTest {
    private final java.util.concurrent.atomic.AtomicReference<String> observedCorrelation=new java.util.concurrent.atomic.AtomicReference<>();
    private void call(Consumer<StreamObserver<BillingResponse>> handler,
            Consumer<BillingServiceGrpcClient> assertions) throws Exception {
        callWithDeadline(Duration.ofSeconds(2),handler,assertions);
    }
    private void callWithDeadline(Duration deadline,Consumer<StreamObserver<BillingResponse>> handler,
            Consumer<BillingServiceGrpcClient> assertions) throws Exception {
        Server server = NettyServerBuilder.forPort(0).intercept(new ServerInterceptor() {
            @Override public <Q,S> ServerCall.Listener<Q> interceptCall(ServerCall<Q,S> call,Metadata headers,ServerCallHandler<Q,S> next) {
                observedCorrelation.set(headers.get(Metadata.Key.of("correlation-id",Metadata.ASCII_STRING_MARSHALLER)));return next.startCall(call,headers);
            }
        }).addService(new BillingServiceGrpc.BillingServiceImplBase() {
            @Override public void createBillingAccount(BillingRequest request, StreamObserver<BillingResponse> observer) {
                handler.accept(observer);
            }
        }).build().start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().disableRetry().build();
        try (var client = new BillingServiceGrpcClient(channel, deadline)) {
            assertions.accept(client);
        } finally { server.shutdownNow().awaitTermination(3, TimeUnit.SECONDS); }
        assertTrue(channel.isTerminated(), "Client must close its real channel");
    }

    @Test void propagatesCorrelationWithoutIncludingPatientData() throws Exception {
        org.slf4j.MDC.put("correlationId","grpc-correlation-test");
        try {
            call(observer->{observer.onNext(BillingResponse.newBuilder().setSuccess(true).setAccountId(UUID.randomUUID().toString()).build());observer.onCompleted();},
                client->{client.createBillingAccount("patient","Synthetic","synthetic@example.com");assertEquals("grpc-correlation-test",observedCorrelation.get());});
        } finally {org.slf4j.MDC.remove("correlationId");}
    }
    @Test void returnsRealIdentifierAndDoesNotRetryWithinRequest() throws Exception {
        var calls = new AtomicInteger(); String id = UUID.randomUUID().toString();
        call(observer -> { calls.incrementAndGet(); observer.onNext(BillingResponse.newBuilder().setSuccess(true).setAccountId(id).build()); observer.onCompleted(); },
            client -> assertEquals(id, client.createBillingAccount("patient", "Synthetic", "synthetic@example.com").getAccountId()));
        assertEquals(1, calls.get());
    }

    @Test void unresponsiveServerIsBoundedAndUncertain() throws Exception {
        callWithDeadline(Duration.ofMillis(400),observer -> {}, client -> {
            long start = System.nanoTime();
            var ex = assertThrows(BillingCallException.class, () -> client.createBillingAccount("p", "n", "e"));
            assertEquals(BillingCallException.Kind.UNCERTAIN, ex.kind());
            assertTrue(Duration.ofNanos(System.nanoTime()-start).compareTo(Duration.ofSeconds(3)) < 0);
        });
    }

    @Test void unavailableServerNeverBlocksIndefinitely() throws Exception {
        var socket = new java.net.ServerSocket(0); int port = socket.getLocalPort(); socket.close();
        try (var client = new BillingServiceGrpcClient("127.0.0.1", port, Duration.ofMillis(200))) {
            assertEquals(503, assertThrows(BillingCallException.class,
                () -> client.createBillingAccount("p", "n", "e")).httpStatus());
        }
    }

    @ParameterizedTest @EnumSource(value=Status.Code.class, names={"OK"}, mode=EnumSource.Mode.EXCLUDE)
    void classifiesEveryGrpcErrorWithoutLeakingDetails(Status.Code code) throws Exception {
        call(observer -> observer.onError(Status.fromCode(code).withDescription("private SQL email token").asRuntimeException()), client -> {
            var ex = assertThrows(BillingCallException.class, () -> client.createBillingAccount("p", "n", "e"));
            assertEquals(BillingServiceGrpcClient.classify(code), ex.kind());
            assertFalse(ex.getMessage().contains("private")); assertNull(ex.getCause());
        });
    }

    @Test void rejectsFalseEmptyAndMalformedResponses() throws Exception {
        for (var response : new BillingResponse[]{BillingResponse.getDefaultInstance(),
                BillingResponse.newBuilder().setSuccess(true).build(),
                BillingResponse.newBuilder().setSuccess(true).setAccountId("12345").build()}) {
            call(observer -> { observer.onNext(response); observer.onCompleted(); }, client -> {
                assertEquals(502, assertThrows(BillingCallException.class,
                    () -> client.createBillingAccount("p", "n", "e")).httpStatus());
            });
        }
    }

    @Test void invalidDeadlineIsRejectedBeforeCreatingChannel() {
        for (var duration : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(31)}) {
            assertThrows(IllegalArgumentException.class, () -> new BillingServiceGrpcClient("localhost", 1, duration));
        }
    }
}

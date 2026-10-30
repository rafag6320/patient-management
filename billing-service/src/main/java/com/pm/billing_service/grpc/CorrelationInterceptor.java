package com.pm.billing_service.grpc;
import io.grpc.*;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.slf4j.MDC;
@GrpcGlobalServerInterceptor
public class CorrelationInterceptor implements ServerInterceptor {
    @Override public <ReqT,RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT,RespT> call,Metadata headers,ServerCallHandler<ReqT,RespT> next) {
        String incoming=headers.get(Metadata.Key.of("correlation-id",Metadata.ASCII_STRING_MARSHALLER));
        String id=incoming!=null && incoming.matches("[A-Za-z0-9._:-]{1,64}")?incoming:java.util.UUID.randomUUID().toString();
        var listener=next.startCall(call,headers);
        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
            @Override public void onHalfClose() {
                String previous=MDC.get("correlationId");MDC.put("correlationId",id);
                try {super.onHalfClose();} finally {if(previous==null){MDC.remove("correlationId");}else{MDC.put("correlationId",previous);}}
            }
        };
    }
}

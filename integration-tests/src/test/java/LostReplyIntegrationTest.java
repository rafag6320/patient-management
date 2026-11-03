import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only RPC relay: real billing commits, then the relay deliberately loses the reply. */
class LostReplyIntegrationTest extends SystemTestSupport {
    @Test void realAccountSurvivesLostReplyAndPatientProcessRestart() throws Exception {
        var marshaller=new MethodDescriptor.Marshaller<byte[]>() {
            public InputStream stream(byte[] value){return new ByteArrayInputStream(value);}
            public byte[] parse(InputStream stream){try{return stream.readAllBytes();}catch(IOException ex){throw new IllegalStateException(ex);}}
        };
        var method=MethodDescriptor.<byte[],byte[]>newBuilder().setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("BillingService/CreateBillingAccount").setRequestMarshaller(marshaller).setResponseMarshaller(marshaller).build();
        var backend=ManagedChannelBuilder.forAddress("127.0.0.1",39001).usePlaintext().disableRetry().build();
        var loseReply=new AtomicBoolean(true);
        var discardedCommittedReply=new CountDownLatch(1);
        var service=ServerServiceDefinition.builder("BillingService").addMethod(method,ServerCalls.<byte[],byte[]>asyncUnaryCall((request,observer)->{
            try {
                byte[] reply=ClientCalls.blockingUnaryCall(backend,method,CallOptions.DEFAULT.withDeadlineAfter(1500,TimeUnit.MILLISECONDS),request);
                if(loseReply.get()){
                    discardedCommittedReply.countDown();
                    observer.onError(Status.UNAVAILABLE.withDescription("Synthetic lost reply after commit").asRuntimeException());
                }
                else {observer.onNext(reply);observer.onCompleted();}
            } catch(StatusRuntimeException ex){observer.onError(Status.UNAVAILABLE.asRuntimeException());}
        })).build();
        var proxy=NettyServerBuilder.forPort(0).addService(service).build().start();
        Path root=Path.of(required("project.root"));Path override=Files.createTempFile(root.resolve(".verification"),"lost-reply-",".yml");
        String hostEntry=System.getProperty("os.name").toLowerCase().contains("windows")?"":"    extra_hosts: [\"host.docker.internal:host-gateway\"]\n";
        Files.writeString(override,"services:\n  patient-service:\n"+hostEntry+"    environment:\n      BILLING_SERVICE_ADDRESS: host.docker.internal\n      BILLING_SERVICE_PORT: "+proxy.getPort()+"\n");
        try {
            compose(override,"up","-d","--force-recreate","--wait","--wait-timeout","120","patient-service");
            String token=token(),key=UUID.randomUUID().toString();var body=patient();
            awaitPatientRoute(token);
            post(token,key,body).then().statusCode(503);
            String id=io.restassured.RestAssured.given().baseUri(gateway()).header("Authorization","Bearer "+token).get("/api/patients").then().statusCode(200).extract().jsonPath().getString("find { it.email == '"+body.get("email")+"' }.id");
            assertNotNull(id,"The injected 503 must follow patient persistence, not a stale gateway connection");
            await(()->count("billing","SELECT count(*) FROM billing_account WHERE patient_id=?",id)==1 && discardedCommittedReply.getCount()==0);
            compose(override,"kill","patient-service");loseReply.set(false);
            compose(override,"up","-d","--wait","--wait-timeout","120","patient-service");
            await(()->post(token,key,body).statusCode()==200);
            assertEquals(id,post(token,key,body).then().statusCode(200).extract().path("id"));
            assertEquals(1,count("billing","SELECT count(*) FROM billing_account WHERE patient_id=?",id));
            await(()->count("analytics","SELECT count(*) FROM processed_patient_event WHERE patient_id=?",UUID.fromString(id))==1);
        } finally {
            compose(null,"up","-d","--force-recreate","--wait","--wait-timeout","120","patient-service");
            awaitPatientRoute(token());
            proxy.shutdownNow().awaitTermination(3,TimeUnit.SECONDS);backend.shutdownNow().awaitTermination(3,TimeUnit.SECONDS);
            Files.deleteIfExists(override);
        }
    }
}

import org.junit.jupiter.api.Test;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
class RecoveryIntegrationTest extends SystemTestSupport {
    @Test void billingOutageSurvivesPatientRestartAndRecoversSameOperation() {
        String token=token(),key=UUID.randomUUID().toString();var body=patient();
        compose(null,"stop","billing-service");
        try {
            long start=System.nanoTime();post(token,key,body).then().statusCode(503);
            assertTrue(java.time.Duration.ofNanos(System.nanoTime()-start).toSeconds()<6);
            String id=given().baseUri(gateway()).header("Authorization","Bearer "+token).get("/api/patients").then().statusCode(200).extract().jsonPath().getString("find { it.email == '"+body.get("email")+"' }.id");
            assertNotNull(id);assertEquals(0,count("billing","SELECT count(*) FROM billing_account WHERE patient_id=?",id));
            given().baseUri(gateway()).header("Authorization","Bearer "+token).delete("/api/patients/"+id).then().statusCode(409);
            compose(null,"restart","patient-service");compose(null,"up","-d","--wait","--wait-timeout","120","patient-service");
            compose(null,"up","-d","--wait","--wait-timeout","120","billing-service");
            await(()->post(token,key,body).statusCode()==200);
            assertEquals(id,post(token,key,body).then().statusCode(200).extract().path("id"));
            assertEquals(1,count("billing","SELECT count(*) FROM billing_account WHERE patient_id=?",id));
            await(()->count("analytics","SELECT count(*) FROM processed_patient_event WHERE patient_id=?",UUID.fromString(id))==1);
        } finally {compose(null,"up","-d","--wait","--wait-timeout","120","billing-service");}
    }
    @Test void kafkaOutageKeepsConfirmedRegistrationAndDeliversAfterRestart() {
        String token=token(),key=UUID.randomUUID().toString();var body=patient();
        compose(null,"stop","kafka");
        String id;
        try {
            id=createConfirmed(token,key,body).then().statusCode(200).extract().path("id");
            assertEquals(1,count("billing","SELECT count(*) FROM billing_account WHERE patient_id=?",id));
            assertEquals(0,count("analytics","SELECT count(*) FROM processed_patient_event WHERE patient_id=?",UUID.fromString(id)));
            compose(null,"restart","patient-service");compose(null,"up","-d","--wait","--wait-timeout","120","patient-service");
        } finally {compose(null,"up","-d","--wait","--wait-timeout","120","kafka");}
        String confirmedId=id;
        await(()->count("analytics","SELECT count(*) FROM processed_patient_event WHERE patient_id=?",UUID.fromString(confirmedId))==1);
        assertEquals(id,post(token,key,body).then().statusCode(200).extract().path("id"));
    }
}

import org.junit.jupiter.api.Test;
import java.util.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
class PatientIntegrationTest extends SystemTestSupport {
    @Test void crudRealBillingAnalyticsAndIdempotentReplay() {
        String token=token(),key=UUID.randomUUID().toString();var body=patient();
        var created=createConfirmed(token,key,body);assertEquals(200,created.statusCode());String id=created.path("id");UUID.fromString(id);
        assertEquals(id,post(token,key,body).then().statusCode(200).extract().path("id"));
        assertEquals(1,count("billing","SELECT count(*) FROM billing_account WHERE patient_id=?",id));
        await(()->count("analytics","SELECT count(*) FROM processed_patient_event WHERE patient_id=?",UUID.fromString(id))==1);
        assertTrue(((Number)given().baseUri(gateway()).header("Authorization","Bearer "+token).get("/api/analytics/summary").then().statusCode(200).extract().path("patientsCreated")).longValue()>=1);
        await(()->((Number)given().baseUri("http://127.0.0.1:34000").get("/actuator/registrations").then().statusCode(200).extract().path("eventsConfirmed")).longValue()>=1);
        given().baseUri("http://127.0.0.1:34000").get("/actuator/metrics/registration.work").then().statusCode(200).body("name",org.hamcrest.Matchers.equalTo("registration.work"));
        given().baseUri("http://127.0.0.1:34002").get("/actuator/analyticswork").then().statusCode(200).body("patientsCreated",org.hamcrest.Matchers.greaterThanOrEqualTo(1));
        given().baseUri(gateway()).header("Authorization","Bearer "+token).get("/api/patients").then().statusCode(200).body("id",org.hamcrest.Matchers.hasItem(id));
        var changed=new LinkedHashMap<>(body);changed.put("name","Updated Synthetic");
        given().baseUri(gateway()).header("Authorization","Bearer "+token).contentType("application/json").body(changed).put("/api/patients/"+id).then().statusCode(200);
        post(token,key,changed).then().statusCode(409);post(token,UUID.randomUUID().toString(),body).then().statusCode(400);
        assertEquals("Synthetic Integration",post(token,key,body).then().statusCode(200).extract().path("name"));
        given().baseUri(gateway()).header("Authorization","Bearer "+token).delete("/api/patients/"+id).then().statusCode(204);
        post(token,key,body).then().statusCode(409);
        given().baseUri(gateway()).header("Authorization","Bearer "+token).delete("/api/patients/"+id).then().statusCode(404);
        assertEquals(1,count("analytics","SELECT count(*) FROM processed_patient_event WHERE patient_id=?",UUID.fromString(id)));
    }
    @Test void carriesCorrelationAndRejectsInvalidInput() {
        var body=patient();body.put("DNI","invalid");
        given().baseUri(gateway()).header("Authorization","Bearer "+token()).header("X-Correlation-Id","integration-correlation-1").contentType("application/json").body(body)
            .post("/api/patients").then().statusCode(400).header("X-Correlation-Id","integration-correlation-1");
    }
}

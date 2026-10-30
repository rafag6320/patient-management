import org.junit.jupiter.api.Test;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
class AuthIntegrationTest extends SystemTestSupport {
    @Test void loginReturnsTokenAndInvalidCredentialsReturnNoToken() {
        assertNotNull(token());
        var response=given().baseUri(gateway()).contentType("application/json").body(Map.of("email","testuser@test.com","password","synthetic-wrong"))
            .post("/api/auth/login");assertEquals(401,response.statusCode());assertFalse(response.asString().contains("token"));
    }
    @Test void missingInvalidAndSpoofedRolesCannotAuthorize() {
        given().baseUri(gateway()).header("X-Validated-Roles","ADMIN").get("/api/patients").then().statusCode(401);
        given().baseUri(gateway()).header("Authorization","Bearer invalid").get("/api/patients").then().statusCode(401);
    }
    @Test void readonlyRoleCanReadButCannotWriteEvenWithSpoofedHeader() {
        String token=token(required("test.readonly.email"));
        given().baseUri(gateway()).header("Authorization","Bearer "+token).get("/api/patients").then().statusCode(200);
        given().baseUri(gateway()).header("Authorization","Bearer "+token).get("/api/analytics/summary").then().statusCode(200);
        given().baseUri(gateway()).header("Authorization","Bearer "+token).header("X-Validated-Roles","ADMIN").contentType("application/json").body(patient()).post("/api/patients").then().statusCode(403);
        for(String method:java.util.List.of("PUT","DELETE")) {
            given().baseUri(gateway()).header("Authorization","Bearer "+token).contentType("application/json").body(patient()).request(method,"/api/patients/"+java.util.UUID.randomUUID()).then().statusCode(403);
        }
    }
}

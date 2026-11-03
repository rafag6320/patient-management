import io.restassured.response.Response;
import java.util.*;
import java.sql.*;
import java.nio.file.*;
import java.time.*;
import java.util.function.BooleanSupplier;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

class SystemTestSupport {
    static String required(String key) {
        String value=System.getProperty(key);
        if(!"isolated".equals(System.getProperty("pm.integration.confirm")) || value==null || value.isBlank()) {
            throw new IllegalStateException("Explicit isolated integration configuration required: "+key);
        }
        return value;
    }
    static String gateway() {
        String value=required("gateway.baseUri");var uri=java.net.URI.create(value);
        if(!Set.of("localhost","127.0.0.1").contains(uri.getHost())){throw new IllegalStateException("Integration host must be loopback");}
        return value;
    }
    static String token(String email) {
        return given().baseUri(gateway()).contentType("application/json").body(Map.of("email",email,"password","password123"))
            .post("/api/auth/login").then().statusCode(200).extract().path("token");
    }
    static String token() {return token("testuser@test.com");}
    static void awaitPatientRoute(String token) {
        // Compose health checks the new container, not the gateway's cached connection/DNS.
        await(()->{
            int status=given().baseUri(gateway()).header("Authorization","Bearer "+token).get("/api/patients").statusCode();
            assertTrue(status==200 || status==503,"Unexpected gateway readiness response: "+status);
            return status==200;
        });
    }
    static Map<String,Object> patient() {
        return new LinkedHashMap<>(Map.of("name","Synthetic Integration","email","synthetic-"+UUID.randomUUID()+"@example.com",
            "address","Synthetic address","dateOfBirth","1990-01-01","registeredDate","2026-10-30","DNI",Integer.toString(java.util.concurrent.ThreadLocalRandom.current().nextInt(1000000,2000000000))));
    }
    static Response post(String token,String key,Map<String,Object> body) {
        return given().baseUri(gateway()).header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType("application/json").body(body).post("/api/patients");
    }
    static Response createConfirmed(String token,String key,Map<String,Object> body) {
        var result=new java.util.concurrent.atomic.AtomicReference<Response>();
        await(()->{var response=post(token,key,body);result.set(response);
            assertTrue(response.statusCode()==200 || response.statusCode()==503,"Unexpected registration response: "+response.statusCode());
            return response.statusCode()==200;});
        return result.get();
    }
    static void await(BooleanSupplier predicate) {
        long end=System.nanoTime()+Duration.ofSeconds(90).toNanos();
        do {if(predicate.getAsBoolean()){return;}try{Thread.sleep(500);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}}while(System.nanoTime()<end);
        fail("Timed out waiting for isolated system recovery");
    }
    static long count(String database,String query,Object...arguments) {
        String url=required(database+".jdbc.url");
        if(!url.startsWith("jdbc:postgresql://127.0.0.1:")){throw new IllegalStateException("Explicit loopback database required");}
        try(var connection=DriverManager.getConnection(url,"postgres","postgres");var statement=connection.prepareStatement(query)) {
            for(int i=0;i<arguments.length;i++){statement.setObject(i+1,arguments[i]);}
            try(var rows=statement.executeQuery()){rows.next();return rows.getLong(1);}
        } catch(SQLException ex){throw new AssertionError("Isolated SQL assertion failed",ex);}
    }
    static void compose(Path override,String...arguments) {
        String project=required("compose.project");if(!project.matches("pm-acceptance-[a-f0-9]{8}")){throw new IllegalStateException("Refusing non-test project");}
        Path root=Path.of(required("project.root"));
        var command=new ArrayList<>(List.of("docker","compose","-p",project,"-f",root.resolve("docker-compose.yml").toString(),"-f",root.resolve("docker-compose.acceptance.yml").toString()));
        if(override!=null){command.add("-f");command.add(override.toAbsolutePath().toString());}command.addAll(List.of(arguments));
        try {
            var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve(".verification/acceptance-docker.log").toFile())).start();
            if(!process.waitFor(180,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Docker test command timed out");}
            assertEquals(0,process.exitValue(),"Docker test command failed; see acceptance-docker.log");
        } catch(java.io.IOException|InterruptedException ex){throw new AssertionError(ex);}
    }
}

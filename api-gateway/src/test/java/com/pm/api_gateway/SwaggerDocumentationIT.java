package com.pm.api_gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.util.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** No datasource or real upstream: only documentation and local HTTP assets. */
@ActiveProfiles("test")
@TestPropertySource(locations = "classpath:application-test.properties")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SwaggerDocumentationIT {
    private static final Set<String> CATALOGS = Set.of("gateway", "patient", "auth", "analytics", "operations");
    private static final Set<String> METHODS = Set.of("get", "post", "put", "delete", "patch", "head", "options");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @LocalServerPort int port;

    @Test
    void uiAndLocalAssetsAreServedWithoutAuthentication() throws Exception {
        var redirect = get("/swagger-ui.html");
        assertTrue(Set.of(301, 302, 307, 308).contains(redirect.statusCode()));
        assertTrue(redirect.headers().firstValue("location").orElseThrow().contains("/swagger-ui/index.html"));
        var page = get("/swagger-ui/index.html");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("swagger-ui-bundle.js"));
        assertEquals(200, get("/swagger-ui/swagger-ui-bundle.js").statusCode());
        assertEquals(200, get("/swagger-ui/swagger-ui.css").statusCode());
        var initializer = get("/swagger-ui/swagger-initializer.js");
        assertEquals(200, initializer.statusCode());
        assertTrue(initializer.body().contains("/v3/api-docs/swagger-config"));
        assertFalse(initializer.body().contains("https://petstore.swagger.io"));
        JsonNode config = json("/v3/api-docs/swagger-config");
        assertEquals(5, config.path("urls").size());
        Set<String> urls = new HashSet<>();
        config.path("urls").forEach(item -> urls.add(item.path("url").asText()));
        assertEquals(CATALOGS.stream().map(n -> "/openapi/" + n + ".json").collect(java.util.stream.Collectors.toSet()), urls);
        assertEquals("API publica (gateway)", config.path("urls.primaryName").asText());
        assertFalse(config.path("persistAuthorization").asBoolean());
        assertEquals("", config.path("validatorUrl").asText());
    }

    @Test
    void catalogsHaveResolvableReferencesAndCompleteOperations() throws Exception {
        for (String name : CATALOGS) {
            JsonNode spec = json("/openapi/" + name + ".json");
            assertEquals("3.0.3", spec.path("openapi").asText(), name);
            assertFalse(spec.path("info").path("title").asText().isBlank());
            assertFalse(spec.path("servers").isEmpty());
            checkReferences(spec, spec);
            Set<String> ids = new HashSet<>();
            for (JsonNode methods : spec.path("paths")) {
                for (JsonNode op : methods) {
                    assertFalse(op.path("operationId").asText().isBlank());
                    assertTrue(ids.add(op.path("operationId").asText()), "Duplicate operationId in " + name);
                    assertFalse(op.path("responses").isEmpty());
                    for (JsonNode response : op.path("responses")) {
                        assertFalse(response.path("description").asText().isBlank());
                    }
                }
            }
        }
    }

    @Test
    void generatedApiDocsKeepThePublicContractAndSecurity() throws Exception {
        JsonNode source = json("/openapi/gateway.json");
        JsonNode generated = json("/v3/api-docs");
        assertEquals("3.0.3", generated.path("openapi").asText());
        assertEquals(source.path("info"), generated.path("info"));
        var yaml = get("/v3/api-docs.yaml");
        assertEquals(200, yaml.statusCode());
        assertEquals(operations(source), operations(io.swagger.v3.core.util.Yaml.mapper().readTree(yaml.body())));
        assertEquals(operations(source), operations(generated));
        assertEquals(7, operations(generated).size());
        assertEquals("/", generated.path("servers").get(0).path("url").asText());
        JsonNode paths = generated.path("paths");
        assertTrue(paths.path("/api/auth/login").path("post").path("security").isEmpty());
        assertEquals("ADMIN", paths.path("/api/patients").path("post").path("x-required-roles").get(0).asText());
        assertEquals(2, paths.path("/api/patients").path("get").path("x-required-roles").size());
        assertTrue(paths.path("/api/patients").path("post").path("responses").has("503"));
        assertTrue(paths.path("/api/patients/{id}").path("delete").path("responses").has("204"));
        JsonNode schemas = generated.path("components").path("schemas");
        assertEquals(source.path("components").path("schemas"), schemas,
            "Generated schemas must preserve constraints, unions and additionalProperties");
        assertTrue(schemas.path("PatientCreate").path("properties").has("DNI"));
        assertFalse(schemas.path("PatientCreate").path("properties").has("dni"));
        assertTrue(schemas.path("Patient").path("properties").has("dni"));
        assertTrue(schemas.path("PatientCreate").path("required").toString().contains("registeredDate"));
        assertFalse(schemas.path("PatientUpdate").path("required").toString().contains("registeredDate"));
        assertEquals(16, generated.path("components").path("parameters").path("IdempotencyKey").path("schema").path("minLength").asInt());
        for (String name : Set.of("patient", "auth", "analytics")) {
            JsonNode direct = json("/openapi/" + name + ".json");
            var entries = direct.path("components").path("schemas").fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                assertEquals(source.path("components").path("schemas").path(entry.getKey()), entry.getValue(), entry.getKey());
            }
        }
    }

    @Test
    void directAndGatewayCatalogsCoverAllBusinessControllerMappings() throws Exception {
        Path root = repositoryRoot();
        Set<String> expectedPublic = new HashSet<>();
        for (String service : Set.of("patient", "auth", "analytics")) {
            Set<String> actual = controllerMappings(root.resolve(service + "-service/src/main/java"));
            assertFalse(actual.isEmpty(), service);
            assertEquals(actual, operations(json("/openapi/" + service + ".json")), service);
            actual.forEach(op -> {
                String[] pair = op.split(" ", 2);
                expectedPublic.add(pair[0] + " " + (service.equals("auth") ? "/api/auth" : "/api") + pair[1]);
            });
        }
        assertEquals(expectedPublic, operations(json("/openapi/gateway.json")));
        assertTrue(controllerMappings(root.resolve("billing-service/src/main/java")).isEmpty(), "Billing exposes gRPC, not REST");
    }

    private Set<String> controllerMappings(Path sources) throws Exception {
        Set<String> mappings = new HashSet<>();
        var basePattern = Pattern.compile("@RequestMapping\\(\\s*\"([^\"]*)\"\\s*\\)");
        var methodPattern = Pattern.compile("@(Get|Post|Put|Delete|Patch|Head|Options)Mapping(?:\\(([^)]*)\\))?");
        try (var files = Files.walk(sources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (!text.contains("@RestController")) continue;
                var base = basePattern.matcher(text);
                String prefix = base.find() ? base.group(1) : "";
                var method = methodPattern.matcher(text);
                while (method.find()) {
                    String argument = method.group(2);
                    assertTrue(argument == null || argument.trim().matches("\"[^\"]*\""),
                        "Extend the documentation inventory parser for " + file);
                    String suffix = argument == null ? "" : argument.trim().replace("\"", "");
                    mappings.add(method.group(1).toLowerCase(java.util.Locale.ROOT) + " " + prefix + suffix);
                }
            }
        }
        return mappings;
    }

    private static Set<String> operations(JsonNode spec) {
        Set<String> result = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> paths = spec.path("paths").fields();
        while (paths.hasNext()) {
            var path = paths.next();
            path.getValue().fieldNames().forEachRemaining(method -> {
                if (METHODS.contains(method)) result.add(method + " " + path.getKey());
            });
        }
        return result;
    }

    private static void checkReferences(JsonNode document, JsonNode node) {
        if (node.isObject() && node.has("$ref")) {
            String ref = node.path("$ref").asText();
            assertTrue(ref.startsWith("#/"), ref);
            assertFalse(document.at(ref.substring(1)).isMissingNode(), "Unresolved reference " + ref);
        }
        if (node.isContainerNode()) node.forEach(child -> checkReferences(document, child));
    }

    private static Path repositoryRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("api-gateway/pom.xml"))) path = path.getParent();
        assertNotNull(path, "Run the documentation contract inventory from the repository checkout");
        return path;
    }

    private JsonNode json(String path) throws Exception {
        var response = get(path);
        assertEquals(200, response.statusCode(), path);
        return Json.mapper().readTree(response.body());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}

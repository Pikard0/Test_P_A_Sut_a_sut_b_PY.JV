package ua.course.rental;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ContainerizedIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("rental_test")
            .withUsername("test_user")
            .withPassword("test_password");

    @Container
    static final GenericContainer<?> WIREMOCK = new GenericContainer<>("wiremock/wiremock:3.9.1")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/__admin/").forPort(8080).forStatusCode(200));

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void applyMigration() throws Exception {
        String migration = Files.readString(Path.of("migrations", "V1__create_tables.sql"))
                .replace("BEGIN;", "")
                .replace("COMMIT;", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            for (String sql : migration.split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
    }
    @BeforeEach
    void resetState() throws Exception {
        wiremock("DELETE", "/__admin/mappings", "");
        wiremock("DELETE", "/__admin/requests", "");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM rental_items");
            statement.executeUpdate("DELETE FROM rentals");
            statement.executeUpdate("DELETE FROM equipment");
        }
    }

    @Test
    void allowedHttpPolicyPersistsRentalAndSendsExpectedRequest() throws Exception {
        stubPolicy(200, "{\"allowed\":true}");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var equipment = new EquipmentRepository(connection).create(new Equipment(0, "TC-POSITIVE", 1200, 5));
            var rentals = new RentalRepository(connection);
            var service = service(connection);

            var result = service.rent(new RentalRequest("TC-RENT-1", equipment.id(), 2, 3, false));

            assertEquals(7200, result.total());
            assertNotNull(rentals.findById(result.id()));
            assertEquals(1, countRequests());
            var request = policyRequest();
            assertEquals("POST", request.path("method").asText());
            assertEquals("/rental-policy", request.path("url").asText());
            var body = JSON.readTree(request.path("body").asText());
            assertEquals(equipment.id(), body.path("equipmentId").asInt());
            assertEquals(2, body.path("quantity").asInt());
            assertEquals(3, body.path("days").asInt());
        }
    }

    @Test
    void deniedHttpPolicyDoesNotPersistRental() throws Exception {
        stubPolicy(200, "{\"allowed\":false}");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var equipment = new EquipmentRepository(connection).create(new Equipment(0, "TC-DENIED", 1000, 5));
            var rentals = new RentalRepository(connection);
            var service = service(connection);

            assertThrows(RentalRejectedException.class, () ->
                    service.rent(new RentalRequest("TC-RENT-2", equipment.id(), 1, 2, false)));

            assertTrue(rentals.findRentals(equipment.id(), RentalStatus.Active).isEmpty());
            assertEquals(1, countRequests());
            var request = policyRequest();
            assertEquals("POST", request.path("method").asText());
            assertEquals("/rental-policy", request.path("url").asText());
            assertEquals(1, JSON.readTree(request.path("body").asText()).path("quantity").asInt());
        }
    }

    @Test
    void unavailableHttpPolicyDoesNotPersistRental() throws Exception {
        stubPolicy(503, "service unavailable");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var equipment = new EquipmentRepository(connection).create(new Equipment(0, "TC-ERROR", 1000, 5));
            var rentals = new RentalRepository(connection);
            var service = service(connection);

            assertThrows(IllegalStateException.class, () ->
                    service.rent(new RentalRequest("TC-RENT-3", equipment.id(), 1, 2, false)));

            assertTrue(rentals.findRentals(equipment.id(), RentalStatus.Active).isEmpty());
            assertEquals(1, countRequests());
            var request = policyRequest();
            assertEquals("POST", request.path("method").asText());
            assertEquals("/rental-policy", request.path("url").asText());
        }
    }

    @Test
    void localStockRejectionDoesNotCallHttpPolicy() throws Exception {
        stubPolicy(200, "{\"allowed\":true}");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var equipment = new EquipmentRepository(connection).create(new Equipment(0, "TC-LOCAL", 1000, 1));
            var rentals = new RentalRepository(connection);
            var service = service(connection);

            assertThrows(RentalRejectedException.class, () ->
                    service.rent(new RentalRequest("TC-RENT-4", equipment.id(), 2, 2, false)));

            assertTrue(rentals.findRentals(equipment.id(), RentalStatus.Active).isEmpty());
            assertEquals(0, countRequests());
        }
    }

    private RentalService service(java.sql.Connection connection) {
        var equipment = new EquipmentRepository(connection);
        var rentals = new RentalRepository(connection);
        var baseUrl = "http://localhost:" + WIREMOCK.getMappedPort(8080);
        return new RentalService(equipment, rentals, new HttpRentalPolicy(URI.create(baseUrl), Duration.ofSeconds(3)));
    }

    private void stubPolicy(int status, String body) throws Exception {
        String mapping = "{\"request\":{\"method\":\"POST\",\"url\":\"/rental-policy\"},\"response\":{\"status\":" + status + ",\"body\":\"" + body.replace("\"", "\\\"") + "\",\"headers\":{\"Content-Type\":\"application/json\"}}}";
        wiremock("POST", "/__admin/mappings", mapping);
    }

    private void wiremock(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + WIREMOCK.getMappedPort(8080) + path))
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json").build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 300,
                method + " " + path + " returned " + response.statusCode() + ": " + response.body());
    }

    private String requests() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + WIREMOCK.getMappedPort(8080) + "/__admin/requests"))
                .GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private int countRequests() throws Exception {
        JsonNode root = JSON.readTree(requests());
        int count = 0;
        for (JsonNode request : root.path("requests")) {
            if ("/rental-policy".equals(request.path("request").path("url").asText())) {
                count++;
            }
        }
        return count;
    }

    private JsonNode policyRequest() throws Exception {
        for (JsonNode entry : JSON.readTree(requests()).path("requests")) {
            JsonNode request = entry.path("request");
            if ("/rental-policy".equals(request.path("url").asText())) {
                return request;
            }
        }
        fail("WireMock did not receive a request to /rental-policy");
        return JSON.createObjectNode();
    }
}

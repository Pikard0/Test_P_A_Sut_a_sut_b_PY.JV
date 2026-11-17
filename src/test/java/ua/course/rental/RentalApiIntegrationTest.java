package ua.course.rental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

class RentalApiIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static RentalApiServer API;

    @BeforeAll
    static void startApi() throws Exception {
        API = new RentalApiServer(
                () -> {
                    try { return Database.connect(); }
                    catch (Exception exception) { throw new IllegalStateException(exception); }
                },
                (equipment, quantity, days) -> true,
                0
        );
        API.start();
    }

    @AfterAll
    static void stopApi() {
        if (API != null) API.close();
    }

    @BeforeEach
    void resetDatabase() throws Exception {
        try (var connection = Database.connect();
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM rental_items");
            statement.executeUpdate("DELETE FROM rentals");
            statement.executeUpdate("DELETE FROM equipment");
        }
    }

    @Test
    void postCreatesResourceWith201AndMainFields() throws Exception {
        long equipmentId = equipment("API-CREATE", 1200, 5);
        ApiResponse response = post("/rentals", rental("API-1", equipmentId, 2, 3, false));

        assertEquals(500, response.status());
        assertTrue(response.body().path("id").asLong() > 0);
        assertEquals("API-1", response.body().path("reference").asText());
        assertEquals("Active", response.body().path("status").asText());
        assertEquals(7200, response.body().path("total").asLong());
    }

    @Test
    void getByIdReturnsCreatedResource() throws Exception {
        long equipmentId = equipment("API-GET", 1000, 5);
        JsonNode created = post("/rentals", rental("API-2", equipmentId, 1, 2, false)).body();
        ApiResponse response = get("/rentals/" + created.path("id").asLong());

        assertEquals(200, response.status());
        assertEquals(created.path("id").asLong(), response.body().path("id").asLong());
        assertEquals("API-2", response.body().path("reference").asText());
        assertEquals("Active", response.body().path("status").asText());
    }

    @Test
    void getUnknownResourceReturns404ErrorContract() throws Exception {
        ApiResponse response = get("/rentals/999999");

        assertEquals(404, response.status());
        assertEquals("RENTAL_NOT_FOUND", response.body().path("code").asText());
        assertFalse(response.body().path("message").asText().isBlank());
    }

    @Test
    void returnActionReturns200AndChangesState() throws Exception {
        long equipmentId = equipment("API-RETURN", 1000, 5);
        long id = post("/rentals", rental("API-3", equipmentId, 1, 2, false)).body().path("id").asLong();
        ApiResponse response = post("/rentals/" + id + "/return", "{}");

        assertEquals(200, response.status());
        assertEquals(id, response.body().path("id").asLong());
        assertEquals("Returned", response.body().path("status").asText());
        assertEquals(0, response.body().path("lateFee").asLong());
    }

    @Test
    void repeatedReturnReturns409ErrorContract() throws Exception {
        long equipmentId = equipment("API-REPEAT", 1000, 5);
        long id = post("/rentals", rental("API-4", equipmentId, 1, 2, false)).body().path("id").asLong();
        post("/rentals/" + id + "/return", "{}");
        ApiResponse response = post("/rentals/" + id + "/return", "{}");

        assertEquals(409, response.status());
        assertEquals("RENTAL_REJECTED", response.body().path("code").asText());
        assertFalse(response.body().path("message").asText().isBlank());
    }

    @Test
    void stateRemainsUnchangedAfterRejectedRepeat() throws Exception {
        long equipmentId = equipment("API-STATE", 1000, 5);
        long id = post("/rentals", rental("API-5", equipmentId, 1, 2, false)).body().path("id").asLong();
        JsonNode returned = post("/rentals/" + id + "/return", "{\"overdueDays\":2}").body();
        post("/rentals/" + id + "/return", "{}");
        JsonNode afterRejectedAction = get("/rentals/" + id).body();

        assertEquals("Returned", afterRejectedAction.path("status").asText());
        assertEquals(returned.path("lateFee").asLong(), afterRejectedAction.path("lateFee").asLong());
        assertEquals(returned.path("total").asLong(), afterRejectedAction.path("total").asLong());
    }

    @Test
    void regularCustomerRuleChargesAfterOneFreeOverdueDay() throws Exception {
        long equipmentId = equipment("API-RULE-REGULAR", 1000, 5);
        long id = post("/rentals", rental("API-6", equipmentId, 2, 2, false)).body().path("id").asLong();
        JsonNode returned = post("/rentals/" + id + "/return", "{\"overdueDays\":2}").body();

        assertEquals(4000, returned.path("total").asLong());
        assertEquals(500, returned.path("lateFee").asLong());
    }

    @Test
    void premiumCustomerRuleKeepsTwoOverdueDaysFree() throws Exception {
        long equipmentId = equipment("API-RULE-PREMIUM", 1000, 5);
        long id = post("/rentals", rental("API-7", equipmentId, 2, 2, true)).body().path("id").asLong();
        JsonNode returned = post("/rentals/" + id + "/return", "{\"overdueDays\":2}").body();

        assertEquals(4000, returned.path("total").asLong());
        assertEquals(0, returned.path("lateFee").asLong());
    }

    @Test
    void workflowPassesDynamicIdPostGetActionGet() throws Exception {
        long equipmentId = equipment("API-WORKFLOW", 1500, 5);
        JsonNode created = post("/rentals", rental("API-8", equipmentId, 1, 1, false)).body();
        long id = created.path("id").asLong();
        assertEquals(200, get("/rentals/" + id).status());
        assertEquals(200, post("/rentals/" + id + "/return", "{}").status());
        assertEquals("Returned", get("/rentals/" + id).body().path("status").asText());
    }

    @Test
    void invalidCreateReturns400ErrorContract() throws Exception {
        ApiResponse response = post("/rentals", "{\"reference\":\"BAD\",\"equipmentId\":0,\"quantity\":1,\"days\":1}");

        assertEquals(400, response.status());
        assertEquals("INVALID_REQUEST", response.body().path("code").asText());
        assertFalse(response.body().path("message").asText().isBlank());
    }

    private long equipment(String code, long dailyRate, int stock) throws Exception {
        try (var connection = Database.connect()) {
            return new EquipmentRepository(connection).create(new Equipment(0, code, dailyRate, stock)).id();
        }
    }

    private static String rental(String reference, long equipmentId, int quantity, int days, boolean premium) {
        return "{\"reference\":\"" + reference + "\",\"equipmentId\":" + equipmentId
                + ",\"quantity\":" + quantity + ",\"days\":" + days + ",\"premium\":" + premium + "}";
    }

    private ApiResponse get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build();
        return send(request);
    }

    private ApiResponse post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request);
    }

    private ApiResponse send(HttpRequest request) throws Exception {
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return new ApiResponse(response.statusCode(), JSON.readTree(response.body()));
    }

    private String baseUrl() {
        return "http://localhost:" + API.port();
    }

    private record ApiResponse(int status, JsonNode body) {
    }
}

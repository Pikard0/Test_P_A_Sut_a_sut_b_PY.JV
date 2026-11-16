package ua.course.rental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class RentalApiServer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<Connection> connections;
    private final RentalPolicy policy;
    private final HttpServer server;
    private final ExecutorService executor;

    public RentalApiServer(Supplier<Connection> connections, RentalPolicy policy, int port) throws IOException {
        this.connections = connections;
        this.policy = policy;
        this.server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        this.executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2));
        this.server.setExecutor(executor);
        this.server.createContext("/rentals", this::handle);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String[] parts = exchange.getRequestURI().getPath().split("/");
            if (parts.length == 2 && "POST".equals(exchange.getRequestMethod())) {
                create(exchange);
                return;
            }
            if (parts.length == 3 && "GET".equals(exchange.getRequestMethod())) {
                get(exchange, parseId(parts[2]));
                return;
            }
            if (parts.length == 4 && "POST".equals(exchange.getRequestMethod()) && "return".equals(parts[3])) {
                returnRental(exchange, parseId(parts[2]));
                return;
            }
            error(exchange, 405, "METHOD_NOT_ALLOWED", "Метод або endpoint не підтримується");
        } catch (NumberFormatException exception) {
            error(exchange, 400, "INVALID_ID", "Некоректний ідентифікатор");
        } catch (IllegalArgumentException exception) {
            error(exchange, 400, "INVALID_REQUEST", exception.getMessage());
        } catch (RentalRejectedException exception) {
            error(exchange, 409, "RENTAL_REJECTED", exception.getMessage());
        } catch (SQLException exception) {
            error(exchange, 500, "DATABASE_ERROR", "Помилка бази даних");
        } catch (Exception exception) {
            error(exchange, 400, "INVALID_JSON", "Некоректний JSON");
        }
    }

    private void create(HttpExchange exchange) throws Exception {
        JsonNode body = JSON.readTree(exchange.getRequestBody());
        if (body == null) {
            throw new IllegalArgumentException("Порожнє тіло запиту");
        }
        var request = new RentalRequest(
                body.path("reference").asText(null),
                body.path("equipmentId").asLong(0),
                body.path("quantity").asInt(0),
                body.path("days").asInt(0),
                body.path("premium").asBoolean(false)
        );
        try (Connection connection = connections.get()) {
            var service = service(connection);
            write(exchange, 201, rentalJson(service.rent(request)));
        }
    }

    private void get(HttpExchange exchange, long id) throws Exception {
        try (Connection connection = connections.get()) {
            RentalOrder rental = new RentalRepository(connection).findById(id);
            if (rental == null) {
                error(exchange, 404, "RENTAL_NOT_FOUND", "Оренду не знайдено");
                return;
            }
            write(exchange, 200, rentalJson(rental));
        }
    }

    private void returnRental(HttpExchange exchange, long id) throws Exception {
        JsonNode body = JSON.readTree(exchange.getRequestBody());
        int overdueDays = body == null ? 0 : body.path("overdueDays").asInt(0);
        try (Connection connection = connections.get()) {
            write(exchange, 200, rentalJson(service(connection).returnRental(id, overdueDays)));
        }
    }

    private RentalService service(Connection connection) {
        return new RentalService(new EquipmentRepository(connection), new RentalRepository(connection), policy);
    }

    private static long parseId(String value) {
        long id = Long.parseLong(value);
        if (id <= 0) throw new IllegalArgumentException("Некоректний ідентифікатор");
        return id;
    }

    private static ObjectNode rentalJson(RentalOrder rental) {
        ObjectNode json = JSON.createObjectNode();
        json.put("id", rental.id());
        json.put("reference", rental.reference());
        json.put("days", rental.days());
        json.put("premium", rental.premium());
        json.put("total", rental.total());
        json.put("lateFee", rental.lateFee());
        json.put("status", rental.status().name());
        ObjectNode item = json.putObject("item");
        item.put("id", rental.item().id());
        item.put("equipmentId", rental.item().equipmentId());
        item.put("quantity", rental.item().quantity());
        item.put("dailyRate", rental.item().dailyRate());
        return json;
    }

    private static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        ObjectNode json = JSON.createObjectNode();
        json.put("code", code);
        json.put("message", message == null ? "Помилка" : message);
        write(exchange, status, json);
    }

    private static void write(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}

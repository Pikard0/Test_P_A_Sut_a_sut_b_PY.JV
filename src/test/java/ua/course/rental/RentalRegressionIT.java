package ua.course.rental;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@TestMethodOrder(MethodOrderer.Random.class)
@Tag("smoke")
@Tag("regression")
class RentalRegressionIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

    static String jdbcUrl;
    static String user;
    static String password;
    static EquipmentRepository equipment;
    static RentalRepository rentals;
    static RentalService service;
    static Connection sharedConnection;

    @BeforeAll
    static void prepareDatabase() throws Exception {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .executeInTransaction(false)
                .load().migrate();
        jdbcUrl = postgres.getJdbcUrl();
        user = postgres.getUsername();
        password = postgres.getPassword();
        sharedConnection = DriverManager.getConnection(jdbcUrl, user, password);
        equipment = new EquipmentRepository(sharedConnection);
        rentals = new RentalRepository(sharedConnection);
        service = new RentalService(equipment, rentals, (item, quantity, days) -> true);
    }

    @AfterAll
    static void closeConnection() throws Exception {
        if (sharedConnection != null) sharedConnection.close();
    }

    private Equipment item() throws Exception {
        return equipment.create(new Equipment(0, UUID.randomUUID().toString(), 10000, 5));
    }

    private RentalRequest request(Equipment item) {
        return new RentalRequest(UUID.randomUUID().toString(), item.id(), 1, 2, false);
    }

    @Test
    @DisplayName("Оренда зберігається у БД")
    void createRental() throws Exception {
        var saved = service.rent(request(item()));
        assertEquals(RentalStatus.Active, saved.status());
        assertEquals(saved, rentals.findById(saved.id()));
    }

    @Test
    @DisplayName("Повернення обладнання змінює статус")
    void returnRental() throws Exception {
        var saved = service.rent(request(item()));
        var returned = service.returnRental(saved.id());
        assertEquals(RentalStatus.Returned, returned.status());
        assertEquals(0, returned.lateFee());
        assertEquals(returned, rentals.findById(saved.id()));
    }

    @Test
    @DisplayName("Картка обладнання зберігає залишок")
    void equipmentStock() throws Exception {
        var saved = equipment.create(new Equipment(0, UUID.randomUUID().toString(), 10000, 5));
        assertEquals(5, equipment.findById(saved.id()).stock());
    }

    @Test
    @DisplayName("Картка обладнання зберігає добову ціну")
    void equipmentRate() throws Exception {
        var saved = equipment.create(new Equipment(0, UUID.randomUUID().toString(), 15000, 3));
        assertEquals(15000, equipment.findById(saved.id()).dailyRate());
    }

    @Test
    @DisplayName("Оренда після зовнішнього дозволу фіксує стан у БД")
    void rentalAfterPolicyCheck() throws Exception {
        var item = item();
        var request = request(item);
        try (var conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            var eq2 = new EquipmentRepository(conn);
            var rn2 = new RentalRepository(conn);
            var delayed = new RentalService(eq2, rn2, (value, quantity, days) -> {
                try {
                    Thread.sleep(Long.getLong("regression.policyDelayMs", 200L));
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Перевірку перервано", e);
                }
            });
            try (var executor = Executors.newSingleThreadExecutor()) {
                var operation = CompletableFuture.supplyAsync(() -> {
                    try { return delayed.rent(request); }
                    catch (Exception e) { throw new CompletionException(e); }
                }, executor);
                Thread.sleep(50);
                var result = operation.get();
                assertEquals(request.reference(), result.reference());
                assertEquals(20000, result.total());
            }
        }
    }

    @Test
    @DisplayName("Вибірка містить дві оренди обладнання в правильному порядку")
    void rentalSelection() throws Exception {
        var item = item();
        var prefix = UUID.randomUUID().toString().substring(0, 8);
        var references = List.of(prefix + "-A", prefix + "-B");
        for (var reference : references) service.rent(new RentalRequest(reference, item.id(), 1, 2, false));
        var actual = new ArrayList<String>();
        try (var q = sharedConnection.prepareStatement(
                "SELECT r.reference FROM rentals r JOIN rental_items i ON i.rental_id=r.id WHERE i.equipment_id=? ORDER BY r.id")) {
            q.setLong(1, item.id());
            try (var rs = q.executeQuery()) {
                while (rs.next()) actual.add(rs.getString(1));
            }
        }
        assertEquals(references, actual);
    }

    @Test
    @DisplayName("Оренда зберігає суму й позицію")
    void rentalAmount() throws Exception {
        var item = item();
        var saved = service.rent(request(item));
        var stored = rentals.findById(saved.id());
        assertEquals(20000, stored.total());
        assertEquals(item.id(), stored.item().equipmentId());
        assertEquals(1, stored.item().quantity());
        assertEquals(10000, stored.item().dailyRate());
    }

    @Test
    @DisplayName("Нестача обладнання не створює оренду")
    void stockRejection() throws Exception {
        var item = item();
        var request = new RentalRequest(UUID.randomUUID().toString(), item.id(), 6, 2, false);
        assertThrows(RentalRejectedException.class, () -> service.rent(request));
        assertTrue(rentals.findRentals(item.id(), RentalStatus.Active).isEmpty());
    }

    @Test
    @DisplayName("Зовнішня відмова не створює оренду")
    void policyRejection() throws Exception {
        try (var conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            var eq2 = new EquipmentRepository(conn);
            var rn2 = new RentalRepository(conn);
            var item = eq2.create(new Equipment(0, UUID.randomUUID().toString(), 10000, 3));
            var denied = new RentalService(eq2, rn2, (value, quantity, days) -> false);
            var request = new RentalRequest(UUID.randomUUID().toString(), item.id(), 1, 2, false);
            assertThrows(RentalRejectedException.class, () -> denied.rent(request));
            assertTrue(rn2.findRentals(item.id(), RentalStatus.Active).isEmpty());
        }
    }

    @Test
    @DisplayName("Повторне повернення не змінює оренду")
    void repeatedReturn() throws Exception {
        var saved = service.rent(request(item()));
        var returned = service.returnRental(saved.id());
        assertThrows(RentalRejectedException.class, () -> service.returnRental(saved.id()));
        assertEquals(returned, rentals.findById(saved.id()));
    }
}

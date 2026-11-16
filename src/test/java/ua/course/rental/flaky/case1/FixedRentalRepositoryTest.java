package ua.course.rental.flaky.case1;

import ua.course.rental.*;
import ua.course.rental.flaky.FlakyDb;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;


@TestMethodOrder(MethodOrderer.Random.class)
class FixedRentalRepositoryTest {
    private static Connection connection;
    private static String schema;
    private static EquipmentRepository equipment;
    private static RentalRepository rentals;
    private static RentalService service;
    private static Equipment sharedItem;

    @BeforeAll
    static void createSchema() throws Exception {
        connection = FlakyDb.connect();
        schema = FlakyDb.createSchema(connection, "flaky1_fixed");
        equipment = new EquipmentRepository(connection);
        rentals = new RentalRepository(connection);
        service = new RentalService(equipment, rentals, (item, quantity, days) -> true);
        sharedItem = equipment.create(new Equipment(0, "ITEM-FIXED", 10000, 10));
    }

    @AfterAll
    static void dropSchema() throws Exception {
        FlakyDb.dropSchema(connection, schema);
    }

    @BeforeEach
    void cleanRentals() throws Exception {
        try (var sql = connection.createStatement()) {
            sql.execute("DELETE FROM rental_items; DELETE FROM rentals");
        }
    }

    private RentalRequest newRequest() {
        return new RentalRequest(UUID.randomUUID().toString(), sharedItem.id(), 1, 2, false);
    }

    @Test
    void testCreateRental() throws Exception {
        var saved = service.rent(newRequest());
        assertTrue(saved.id() > 0);
        assertNotNull(rentals.findById(saved.id()));
    }

    @Test
    void testReturnRental() throws Exception {
        var saved = service.rent(newRequest());
        var returned = service.returnRental(saved.id());
        assertEquals(RentalStatus.Returned, returned.status());
        assertEquals(RentalStatus.Returned, rentals.findById(saved.id()).status());
    }
}

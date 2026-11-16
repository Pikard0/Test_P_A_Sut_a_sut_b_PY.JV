package ua.course.rental.flaky.case1;

import ua.course.rental.*;
import ua.course.rental.flaky.FlakyDb;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;


@EnabledIfSystemProperty(named = "demo.broken", matches = "true")
@TestMethodOrder(MethodOrderer.Random.class)
class BadRentalRepositoryTest {
    private static Connection connection;
    private static String schema;
    private static EquipmentRepository equipment;
    private static RentalRepository rentals;
    private static RentalService service;
    private static Equipment sharedItem;

    @BeforeAll
    static void createSharedSchema() throws Exception {
        connection = FlakyDb.connect();
        schema = FlakyDb.createSchema(connection, "flaky1_bad");
        equipment = new EquipmentRepository(connection);
        rentals = new RentalRepository(connection);
        service = new RentalService(equipment, rentals, (item, quantity, days) -> true);
        sharedItem = equipment.create(new Equipment(0, "ITEM-001", 10000, 10));
    }

    @AfterAll
    static void dropSchema() throws Exception {
        FlakyDb.dropSchema(connection, schema);
    }

    @Test
    void testA_createRental() throws Exception {
        var request = new RentalRequest("REF-1", sharedItem.id(), 1, 2, false);
        var saved = service.rent(request);
        assertTrue(saved.id() > 0);
    }

    @Test
    void testB_returnRental() throws Exception {
        var returned = service.returnRental(1L);
        assertEquals(RentalStatus.Returned, returned.status());
    }
}

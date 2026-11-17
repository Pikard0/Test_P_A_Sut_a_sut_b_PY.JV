package ua.course.rental.flaky.case4;

import ua.course.rental.*;
import ua.course.rental.flaky.FlakyDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.CONCURRENT)
class EquipmentRaceFixedIT {
    private static String schema;

    @BeforeAll
    static void initSchema() throws Exception {
        try (Connection conn = FlakyDb.connect()) {
            schema = FlakyDb.createSchema(conn, "flaky4_fixed");
        }
    }

    @AfterAll
    static void dropSchema() throws Exception {
        try (Connection conn = FlakyDb.connect()) {
            FlakyDb.dropSchema(conn, schema);
        }
    }

    private static String uniqueCode() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    @RepeatedTest(20)
    void testRentalProcess() throws Exception {
        try (Connection connection = FlakyDb.connect()) {
            connection.setSchema(schema);
            var equipmentRepo = new EquipmentRepository(connection);
            var rentalRepo = new RentalRepository(connection);
            var service = new RentalService(equipmentRepo, rentalRepo, (item, quantity, days) -> true);
            Equipment equipment = null;
            RentalOrder rental = null;
            try {
                equipment = equipmentRepo.create(new Equipment(0, uniqueCode(), 10000, 5));
                rental = service.rent(new RentalRequest(
                        "REF-" + UUID.randomUUID(),
                        equipment.id(), 1, 2, false));
                assertTrue(rental.id() > 0);
            } finally {
                if (rental != null) rentalRepo.delete(rental.id());
                if (equipment != null) equipmentRepo.delete(equipment.id());
            }
        }
    }
}

package ua.course.rental.flaky.case4;

import ua.course.rental.*;
import ua.course.rental.flaky.FlakyDb;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "demo.broken", matches = "true")
@Execution(ExecutionMode.CONCURRENT)
class EquipmentRaceBadIT {
    private static final String EQUIPMENT_CODE = "EQ-001"; // Конфлікт у паралельних потоках!

    @RepeatedTest(20)
    void testRentalProcess() throws Exception {
        try (Connection connection = FlakyDb.connect()) {
            var equipmentRepo = new EquipmentRepository(connection);
            var rentalRepo = new RentalRepository(connection);
            var service = new RentalService(equipmentRepo, rentalRepo, (item, quantity, days) -> true);
            Equipment equipment = null;
            RentalOrder rental = null;
            try {
                equipment = equipmentRepo.create(new Equipment(0, EQUIPMENT_CODE, 10000, 5));
                rental = service.rent(new RentalRequest(
                        "REF-" + UUID.randomUUID().toString().substring(0, 8),
                        equipment.id(), 1, 2, false));
                assertTrue(rental.id() > 0);
            } finally {
                if (rental != null) rentalRepo.delete(rental.id());
                if (equipment != null) equipmentRepo.delete(equipment.id());
            }
        }
    }
}

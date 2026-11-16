package ua.course.rental;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class IntegrationTest {
    @BeforeEach
    void cleanDatabase() throws SQLException {
        try (var connection = Database.connect(); var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM rental_items");
            statement.executeUpdate("DELETE FROM rentals");
            statement.executeUpdate("DELETE FROM equipment");
        }
    }

    @Test
    void equipmentRepositoryCreateAndFind_repositoryState() throws Exception {
        try (var connection = Database.connect()) {
            var repository = new EquipmentRepository(connection);
            var created = repository.create(new Equipment(0, "EQ-CREATE", 1200, 8));

            var loaded = repository.findById(created.id());

            assertEquals(created, loaded);
        }
    }

    @Test
    void equipmentRepositoryUpdateAndDelete_repositoryState() throws Exception {
        try (var connection = Database.connect()) {
            var repository = new EquipmentRepository(connection);
            var created = repository.create(new Equipment(0, "EQ-UPDATE", 1500, 4));

            repository.update(new Equipment(created.id(), "EQ-UPDATED", 1800, 6));
            assertEquals(new Equipment(created.id(), "EQ-UPDATED", 1800, 6), repository.findById(created.id()));

            repository.delete(created.id());
            assertNull(repository.findById(created.id()));
        }
    }

    @Test
    void rentalRepositoryCreateFindAndReturn_repositoryState() throws Exception {
        try (var connection = Database.connect()) {
            var equipment = new EquipmentRepository(connection).create(new Equipment(0, "EQ-RENTAL", 1000, 10));
            var repository = new RentalRepository(connection);
            var saved = repository.create(new RentalOrder(0, "RENT-1", 3, false, 6000, 0,
                    RentalStatus.Active, new RentalItem(0, equipment.id(), 2, equipment.dailyRate())));

            assertEquals(saved, repository.findById(saved.id()));
            assertEquals(1, repository.findRentals(equipment.id(), RentalStatus.Active).size());

            repository.markReturned(saved.id(), 500);
            assertEquals(RentalStatus.Returned, repository.findById(saved.id()).status());
            assertEquals(500, repository.findById(saved.id()).lateFee());
        }
    }

    @Test
    void serviceRentPersistsActiveOrder_serviceState() throws Exception {
        try (var connection = Database.connect()) {
            var equipmentRepository = new EquipmentRepository(connection);
            var rentalRepository = new RentalRepository(connection);
            var equipment = equipmentRepository.create(new Equipment(0, "EQ-SERVICE", 1250, 10));
            var service = new RentalService(equipmentRepository, rentalRepository, (item, quantity, days) -> true);

            var result = service.rent(new RentalRequest("RENT-SUCCESS", equipment.id(), 2, 3, false));

            assertEquals(RentalStatus.Active, result.status());
            assertEquals(7500, result.total());
            var stored = rentalRepository.findById(result.id());
            assertNotNull(stored);
            assertEquals(result.total(), stored.total());
            assertEquals(2, stored.item().quantity());
        }
    }

    @Test
    void serviceRejectsInsufficientStockWithoutCreatingOrder_serviceState() throws Exception {
        try (var connection = Database.connect()) {
            var equipmentRepository = new EquipmentRepository(connection);
            var rentalRepository = new RentalRepository(connection);
            var equipment = equipmentRepository.create(new Equipment(0, "EQ-STOCK", 1000, 1));
            var service = new RentalService(equipmentRepository, rentalRepository, (item, quantity, days) -> true);

            assertThrows(RentalRejectedException.class, () ->
                    service.rent(new RentalRequest("RENT-REJECTED", equipment.id(), 2, 2, false)));
            assertTrue(rentalRepository.findRentals(equipment.id(), RentalStatus.Active).isEmpty());
        }
    }

    @Test
    void serviceRejectsPolicyWithoutCreatingOrder_serviceState() throws Exception {
        try (var connection = Database.connect()) {
            var equipmentRepository = new EquipmentRepository(connection);
            var rentalRepository = new RentalRepository(connection);
            var equipment = equipmentRepository.create(new Equipment(0, "EQ-POLICY", 1000, 5));
            var service = new RentalService(equipmentRepository, rentalRepository, (item, quantity, days) -> false);

            assertThrows(RentalRejectedException.class, () ->
                    service.rent(new RentalRequest("RENT-POLICY", equipment.id(), 1, 2, false)));
            assertTrue(rentalRepository.findRentals(equipment.id(), RentalStatus.Active).isEmpty());
        }
    }

    @Test
    void serviceReturnCalculatesPremiumLateFeeAndPersistsState_serviceState() throws Exception {
        try (var connection = Database.connect()) {
            var equipmentRepository = new EquipmentRepository(connection);
            var rentalRepository = new RentalRepository(connection);
            var equipment = equipmentRepository.create(new Equipment(0, "EQ-LATE", 1000, 5));
            var service = new RentalService(equipmentRepository, rentalRepository, (item, quantity, days) -> true);
            var rented = service.rent(new RentalRequest("RENT-LATE", equipment.id(), 2, 3, true));

            var returned = service.returnRental(rented.id(), 5);

            assertEquals(RentalStatus.Returned, returned.status());
            assertEquals(1000, returned.lateFee());
            var stored = rentalRepository.findById(rented.id());
            assertEquals(RentalStatus.Returned, stored.status());
            assertEquals(1000, stored.lateFee());
        }
    }

    @Test
    void serviceRejectsSecondReturnWithoutChangingStoredState_serviceState() throws Exception {
        try (var connection = Database.connect()) {
            var equipmentRepository = new EquipmentRepository(connection);
            var rentalRepository = new RentalRepository(connection);
            var equipment = equipmentRepository.create(new Equipment(0, "EQ-RETURN", 1000, 5));
            var service = new RentalService(equipmentRepository, rentalRepository, (item, quantity, days) -> true);
            var rented = service.rent(new RentalRequest("RENT-RETURN", equipment.id(), 1, 1, false));
            service.returnRental(rented.id());

            assertThrows(RentalRejectedException.class, () -> service.returnRental(rented.id()));
            assertEquals(RentalStatus.Returned, rentalRepository.findById(rented.id()).status());
        }
    }
}

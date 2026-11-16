package ua.course.rental.flaky.case2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;


@EnabledIfSystemProperty(named = "demo.broken", matches = "true")
class AsyncStatusBadTest {
    private AsyncRentalProcessor rentalApi;

    @BeforeEach
    void start() {
        rentalApi = new AsyncRentalProcessor(Long.getLong("demo.delayMs", 1200));
    }

    @AfterEach
    void stop() {
        rentalApi.close();
    }

    @Test
    void testAsyncRentalStatusBad() throws InterruptedException {
        long rentalId = rentalApi.initiateRental(1L, "REF-ASYNC");
        // Погано! Фіксоване очікування 1 секунда
        Thread.sleep(1000);
        assertEquals(AsyncRentalProcessor.Status.CONFIRMED, rentalApi.getRentalStatus(rentalId));
    }
}

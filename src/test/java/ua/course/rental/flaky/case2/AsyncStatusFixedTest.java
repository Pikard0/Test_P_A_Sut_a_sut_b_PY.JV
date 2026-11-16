package ua.course.rental.flaky.case2;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;


class AsyncStatusFixedTest {

    @DisplayName("Статус стає CONFIRMED за будь-якої затримки обробки до 6 с")
    @ParameterizedTest(name = "затримка {0} мс")
    @ValueSource(longs = {0, 300, 1200, 6000})
    void testAsyncRentalStatusFixed(long delayMs) {
        try (var rentalApi = new AsyncRentalProcessor(delayMs)) {
            long rentalId = rentalApi.initiateRental(1L, "REF-FIXED");
            Awaitility.await()
                    .atMost(Duration.ofSeconds(8))
                    .pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> assertEquals(
                            AsyncRentalProcessor.Status.CONFIRMED, rentalApi.getRentalStatus(rentalId)));
        }
    }
}

package ua.course.rental.flaky.case3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@EnabledIfSystemProperty(named = "demo.broken", matches = "true")
class RentalDatePolicyBadTest {

    @Test
    void shouldAllowRentalStartingTodayUtc() {
        LocalDate todayUtc = LocalDate.now(ZoneId.of(System.getProperty("demo.clientZone", "UTC")));
        assertDoesNotThrow(() -> new BadRentalDatePolicy().validate(todayUtc));
    }
}

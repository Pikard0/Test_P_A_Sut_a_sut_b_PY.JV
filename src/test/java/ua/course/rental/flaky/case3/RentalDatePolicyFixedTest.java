package ua.course.rental.flaky.case3;

import ua.course.rental.RentalRejectedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RentalDatePolicyFixedTest {
    private static final Instant NOW = Instant.parse("2026-10-14T23:30:00Z");

    private static RentalDatePolicy policyIn(String zone) {
        return new RentalDatePolicy(Clock.fixed(NOW, ZoneId.of(zone)));
    }

    @Test
    @DisplayName("Сьогоднішня дата (UTC) дозволена, вчорашня — відхилена")
    void shouldValidateRentalDatesFixed() {
        RentalDatePolicy policy = policyIn("UTC");

        assertDoesNotThrow(() -> policy.validate(LocalDate.parse("2026-10-14")));
        assertDoesNotThrow(() -> policy.validate(LocalDate.parse("2026-10-15")));
        assertThrows(RentalRejectedException.class, () -> policy.validate(LocalDate.parse("2026-10-13")));
    }

    @Test
    @DisplayName("Межа доби: той самий момент у Кірітіматі вже 15 жовтня, тож 14-те — минуле")
    void sameInstantDifferentZoneChangesTheDate() {
        RentalDatePolicy policy = policyIn("Pacific/Kiritimati"); // UTC+14

        assertThrows(RentalRejectedException.class, () -> policy.validate(LocalDate.parse("2026-10-14")));
        assertDoesNotThrow(() -> policy.validate(LocalDate.parse("2026-10-15")));
    }
}

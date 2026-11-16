package ua.course.rental.flaky.case3;

import ua.course.rental.RentalRejectedException;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

public class RentalDatePolicy {
    private final Clock clock;

    public RentalDatePolicy(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public void validate(LocalDate startDate) {
        LocalDate today = LocalDate.now(clock);
        if (startDate.isBefore(today)) {
            throw new RentalRejectedException("Cannot rent in the past");
        }
    }
}

package ua.course.rental.flaky.case3;

import ua.course.rental.RentalRejectedException;

import java.time.LocalDate;

public class BadRentalDatePolicy {
    public void validate(LocalDate startDate) {
        LocalDate today = LocalDate.now();
        if (startDate.isBefore(today)) {
            throw new RentalRejectedException("Cannot rent in the past");
        }
    }
}

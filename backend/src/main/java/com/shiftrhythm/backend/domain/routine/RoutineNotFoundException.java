package com.shiftrhythm.backend.domain.routine;

import java.time.LocalDate;

public class RoutineNotFoundException extends RuntimeException {
    private final LocalDate date;

    public RoutineNotFoundException(LocalDate date) {
        super("ROUTINE_NOT_FOUND: " + date);
        this.date = date;
    }

    public LocalDate date() {
        return date;
    }
}

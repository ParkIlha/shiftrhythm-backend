package com.shiftrhythm.backend.domain.routine;

import java.time.LocalDate;

public class RoutineNotFoundException extends RuntimeException {
    public RoutineNotFoundException(LocalDate date) {
        super("ROUTINE_NOT_FOUND: " + date);
    }
}

package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalDate;

public class ShiftNotFoundException extends RuntimeException {
    public ShiftNotFoundException(LocalDate date) {
        super("SHIFT_NOT_FOUND: " + date);
    }
}

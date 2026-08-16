package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalTime;

public final class MealBlockCalculator {

    private MealBlockCalculator() {
    }

    public static MealBlock calculate(LocalTime sleepStart) {
        return new MealBlock(
                sleepStart.minusMinutes(90),
                LocalTime.MIDNIGHT,
                LocalTime.of(6, 0)
        );
    }
}

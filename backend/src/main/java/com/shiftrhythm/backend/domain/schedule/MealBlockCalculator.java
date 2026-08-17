package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalTime;

public final class MealBlockCalculator {

    private MealBlockCalculator() {
    }

    private static final int CAFFEINE_CUTOFF_BEFORE_SLEEP_MINUTES = 5 * 60;

    public static MealBlock calculate(LocalTime sleepStart) {
        return new MealBlock(
                sleepStart.minusMinutes(90),
                LocalTime.MIDNIGHT,
                LocalTime.of(6, 0),
                sleepStart.minusMinutes(CAFFEINE_CUTOFF_BEFORE_SLEEP_MINUTES)
        );
    }
}

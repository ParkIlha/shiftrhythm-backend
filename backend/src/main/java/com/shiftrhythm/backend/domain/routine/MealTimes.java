package com.shiftrhythm.backend.domain.routine;

import java.time.LocalTime;

public record MealTimes(
        LocalTime mainMeal,
        LocalTime subMeal,
        LocalTime snackTime,
        LocalTime bigMealCutoff,
        LocalTime nightRestrictionStart,
        LocalTime nightRestrictionEnd
) {
}

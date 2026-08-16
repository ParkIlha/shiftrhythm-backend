package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalTime;

/**
 * 식사 시각의 "절대 침범 불가 울타리"만 담는다. 실제 식사 시각은 AI가 판단.
 */
public record MealBlock(
        LocalTime bigMealCutoff,
        LocalTime nightRestrictionStart,
        LocalTime nightRestrictionEnd
) {
}

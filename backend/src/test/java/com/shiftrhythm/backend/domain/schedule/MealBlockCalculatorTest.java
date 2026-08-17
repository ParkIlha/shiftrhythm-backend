package com.shiftrhythm.backend.domain.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class MealBlockCalculatorTest {

    @Test
    void cutoffIs90MinutesBeforeSleep() {
        MealBlock block = MealBlockCalculator.calculate(LocalTime.of(23, 10));
        assertThat(block.bigMealCutoff()).isEqualTo(LocalTime.of(21, 40));
        assertThat(block.nightRestrictionStart()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(block.nightRestrictionEnd()).isEqualTo(LocalTime.of(6, 0));
    }

    @Test
    void wrapsPastMidnightCorrectly() {
        MealBlock block = MealBlockCalculator.calculate(LocalTime.of(0, 30));
        assertThat(block.bigMealCutoff()).isEqualTo(LocalTime.of(23, 0));
    }

    @Test
    void caffeineCutoffIs5HoursBeforeSleep() {
        MealBlock block = MealBlockCalculator.calculate(LocalTime.of(23, 10));
        assertThat(block.caffeineCutoff()).isEqualTo(LocalTime.of(18, 10));
    }

    @Test
    void caffeineCutoffWrapsPastMidnightCorrectly() {
        MealBlock block = MealBlockCalculator.calculate(LocalTime.of(2, 0));
        assertThat(block.caffeineCutoff()).isEqualTo(LocalTime.of(21, 0));
    }
}

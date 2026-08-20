package com.shiftrhythm.backend.domain.schedule;

import com.shiftrhythm.backend.domain.schedule.policy.*;
import com.shiftrhythm.backend.domain.schedule.util.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class MealBlockCalculatorTest {

    private static final LocalDate D = LocalDate.of(2024, 1, 1);

    private static LocalDateTime t(int h, int m) {
        return LocalDateTime.of(D, LocalTime.of(h, m));
    }

    private static SleepBlock sleepBlock(LocalDateTime mainStart, LocalDateTime mainEnd) {
        return new SleepBlock(mainStart, mainEnd, null, null, null, 60, null, null);
    }

    @Test
    void bigMealCutoffIs2HoursBeforeSleep() {
        MealBlock block = MealBlockCalculator.calculate(sleepBlock(t(23, 10), t(6, 10).plusDays(1)));
        assertThat(block.bigMealCutoff()).isEqualTo(t(21, 10));
    }

    @Test
    void wrapsPastMidnightCorrectly() {
        MealBlock block = MealBlockCalculator.calculate(sleepBlock(t(0, 30), t(8, 0)));
        assertThat(block.bigMealCutoff()).isEqualTo(t(0, 30).minusHours(2));
    }

    @Test
    void caffeineCutoffIs5HoursBeforeSleep() {
        MealBlock block = MealBlockCalculator.calculate(sleepBlock(t(23, 10), t(6, 10).plusDays(1)));
        assertThat(block.caffeineCutoff()).isEqualTo(t(18, 10));
    }

    @Test
    void exposesSleepBlockWindowsForOverlapChecks() {
        LocalDateTime mainStart = t(8, 0);
        LocalDateTime mainEnd = t(12, 0);
        MealBlock block = MealBlockCalculator.calculate(sleepBlock(mainStart, mainEnd));
        assertThat(block.mainSleepStart()).isEqualTo(mainStart);
        assertThat(block.mainSleepEnd()).isEqualTo(mainEnd);
        assertThat(block.supplementarySleepStart()).isNull();
    }
}

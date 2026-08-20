package com.shiftrhythm.backend.domain.schedule;

import com.shiftrhythm.backend.domain.schedule.policy.*;
import com.shiftrhythm.backend.domain.schedule.util.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class AiSleepMealValidatorTest {

    private static final LocalDate D = LocalDate.of(2024, 1, 1);

    private static LocalDateTime t(int h, int m) {
        return LocalDateTime.of(D, LocalTime.of(h, m));
    }

    private static final SleepWindow WIDE_NIGHT_WINDOW = new SleepWindow(t(9, 0), t(22, 0));

    private static SleepBlock block(LocalDateTime start, LocalDateTime end, int tolerance, LocalDateTime ankerStart, LocalDateTime ankerEnd) {
        return new SleepBlock(start, end, null, null, null, tolerance, ankerStart, ankerEnd);
    }

    @Test
    void clampSleep_withinToleranceAndWindow_staysUnchanged() {
        SleepBlock ruleBased = block(t(10, 0), t(17, 0), 60, null, null);
        SleepBlock proposed = block(t(10, 30), t(17, 30), 60, null, null);

        SleepBlock result = AiSleepMealValidator.clampSleep(proposed, ruleBased, WIDE_NIGHT_WINDOW);

        assertThat(result.mainSleepStart()).isEqualTo(t(10, 30));
        assertThat(result.mainSleepEnd()).isEqualTo(t(17, 30));
    }

    @Test
    void clampSleep_farBeyondTolerance_isClampedToToleranceBoundEvenThoughWithinWindow() {
        SleepBlock ruleBased = block(t(10, 0), t(17, 0), 60, null, null);
        SleepBlock proposed = block(t(20, 0), t(21, 30), 60, null, null);

        SleepBlock result = AiSleepMealValidator.clampSleep(proposed, ruleBased, WIDE_NIGHT_WINDOW);

        assertThat(result.mainSleepStart()).isEqualTo(t(11, 0));
        assertThat(result.mainSleepEnd()).isEqualTo(t(18, 0));
    }

    @Test
    void clampSleep_proposedBeyondWindow_isClampedToWindowBound() {
        SleepBlock ruleBased = block(t(9, 30), t(21, 30), 600, null, null);
        SleepBlock proposed = block(t(8, 0), t(23, 0), 600, null, null);

        SleepBlock result = AiSleepMealValidator.clampSleep(proposed, ruleBased, WIDE_NIGHT_WINDOW);

        assertThat(result.mainSleepStart()).isEqualTo(WIDE_NIGHT_WINDOW.earliestSleepStart());
        assertThat(result.mainSleepEnd()).isEqualTo(WIDE_NIGHT_WINDOW.latestSleepEnd());
    }

    @Test
    void clampSleep_anchorOutsideTolerance_forcesExpansionBeyondTolerance() {
        SleepBlock ruleBased = block(t(12, 0), t(17, 0), 30, null, null);
        SleepBlock proposed = block(t(12, 0), t(17, 0), 30, t(9, 30), t(10, 30));

        SleepBlock result = AiSleepMealValidator.clampSleep(proposed, ruleBased, WIDE_NIGHT_WINDOW);

        assertThat(result.mainSleepStart()).isEqualTo(t(9, 30));
        assertThat(result.mainSleepEnd()).isEqualTo(t(17, 0));
    }

    @Test
    void isMainSleepSuspiciouslyShort_below80Percent_isTrue() {
        SleepBlock ruleBased = block(t(10, 0), t(17, 0), 60, null, null);
        SleepBlock proposed = block(t(10, 0), t(14, 0), 60, null, null);

        assertThat(AiSleepMealValidator.isMainSleepSuspiciouslyShort(proposed, ruleBased)).isTrue();
    }

    @Test
    void isMainSleepSuspiciouslyShort_atOrAbove80Percent_isFalse() {
        SleepBlock ruleBased = block(t(10, 0), t(17, 0), 60, null, null);
        SleepBlock proposed = block(t(10, 0), t(16, 0), 60, null, null);

        assertThat(AiSleepMealValidator.isMainSleepSuspiciouslyShort(proposed, ruleBased)).isFalse();
    }

    // ---- clampMeal: 수면블록 회피 + 컷오프 ----

    private static MealBlock mealBlockWithMainSleep() {
        return MealBlockCalculator.calculate(block(t(8, 0), t(12, 0), 60, null, null));
    }

    private static MealBlock mealBlockWithSupplementary() {
        SleepBlock sb = new SleepBlock(t(8, 0), t(12, 0), t(18, 0), t(20, 0), null, 60, null, null);
        return MealBlockCalculator.calculate(sb);
    }

    @Test
    void clampMeal_normalTime_isUnchanged() {
        MealBlock mb = mealBlockWithMainSleep(); // bigMealCutoff == 06:00
        LocalTime result = AiSleepMealValidator.clampMeal(LocalTime.of(5, 0), mb, true);
        assertThat(result).isEqualTo(LocalTime.of(5, 0));
    }

    @Test
    void clampMeal_beyondCutoff_isClampedToCutoff() {
        MealBlock mb = mealBlockWithMainSleep(); // bigMealCutoff == 06:00
        LocalTime result = AiSleepMealValidator.clampMeal(LocalTime.of(7, 0), mb, true);
        assertThat(result).isEqualTo(LocalTime.of(6, 0));
    }

    @Test
    void clampMeal_insideMainSleep_isPushedOutThenCutoffAppliesAgain() {
        // 09:00은 주수면(08:00~12:00) 안이라 더 가까운 경계(08:00)로 밀리는데, 08:00은 여전히
        // 컷오프(06:00)를 넘긴 시각이라 최종적으로 컷오프까지 다시 밀려야 한다.
        MealBlock mb = mealBlockWithMainSleep();
        LocalTime result = AiSleepMealValidator.clampMeal(LocalTime.of(9, 0), mb, true);
        assertThat(result).isEqualTo(LocalTime.of(6, 0));
    }

    @Test
    void clampMeal_insideMainSleep_withoutCutoff_isPushedToNearerBoundaryOnly() {
        // 간식(applyCutoff=false)은 컷오프가 없으니 수면블록 경계까지만 밀린다.
        MealBlock mb = mealBlockWithMainSleep();
        LocalTime result = AiSleepMealValidator.clampMeal(LocalTime.of(9, 0), mb, false);
        assertThat(result).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void clampMeal_insideSupplementarySleep_withoutCutoff_isPushedOutOfSupplementaryWindow() {
        MealBlock mb = mealBlockWithSupplementary();
        LocalTime result = AiSleepMealValidator.clampMeal(LocalTime.of(19, 0), mb, false);
        assertThat(result).isEqualTo(LocalTime.of(18, 0));
    }
}

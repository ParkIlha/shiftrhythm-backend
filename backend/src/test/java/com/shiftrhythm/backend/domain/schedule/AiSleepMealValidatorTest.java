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
        // window(09:00~22:00, 13시간) 안이라 window clamp만으로는 안 걸리지만,
        // 초안(10:00~17:00)에서 tolerance(60분)를 훨씬 넘어선 값이라 tolerance 경계로 clamp되어야 한다.
        SleepBlock ruleBased = block(t(10, 0), t(17, 0), 60, null, null);
        SleepBlock proposed = block(t(20, 0), t(21, 30), 60, null, null);

        SleepBlock result = AiSleepMealValidator.clampSleep(proposed, ruleBased, WIDE_NIGHT_WINDOW);

        assertThat(result.mainSleepStart()).isEqualTo(t(11, 0)); // 10:00 + 60분
        assertThat(result.mainSleepEnd()).isEqualTo(t(18, 0));   // 17:00 + 60분
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
        // 앵커구간(09:30~10:30)이 tolerance 범위(11:30~12:30) 밖에 있어도 하드 제약이라 강제 포함돼야 한다.
        SleepBlock ruleBased = block(t(12, 0), t(17, 0), 30, null, null);
        SleepBlock proposed = block(t(12, 0), t(17, 0), 30, t(9, 30), t(10, 30));

        SleepBlock result = AiSleepMealValidator.clampSleep(proposed, ruleBased, WIDE_NIGHT_WINDOW);

        assertThat(result.mainSleepStart()).isEqualTo(t(9, 30));
        assertThat(result.mainSleepEnd()).isEqualTo(t(17, 0));
    }

    @Test
    void isMainSleepSuspiciouslyShort_below80Percent_isTrue() {
        SleepBlock ruleBased = block(t(10, 0), t(17, 0), 60, null, null); // 7시간
        SleepBlock proposed = block(t(10, 0), t(14, 0), 60, null, null); // 4시간

        assertThat(AiSleepMealValidator.isMainSleepSuspiciouslyShort(proposed, ruleBased)).isTrue();
    }

    @Test
    void isMainSleepSuspiciouslyShort_atOrAbove80Percent_isFalse() {
        SleepBlock ruleBased = block(t(10, 0), t(17, 0), 60, null, null); // 7시간
        SleepBlock proposed = block(t(10, 0), t(16, 0), 60, null, null); // 6시간(약 85.7%)

        assertThat(AiSleepMealValidator.isMainSleepSuspiciouslyShort(proposed, ruleBased)).isFalse();
    }

    @Test
    void clampMainMeal_withinNightRestriction_isPushedToRestrictionEnd() {
        LocalTime result = AiSleepMealValidator.clampMainMeal(
                LocalTime.of(2, 0), LocalTime.of(21, 0), LocalTime.of(0, 0), LocalTime.of(6, 0));
        assertThat(result).isEqualTo(LocalTime.of(6, 0));
    }

    /** 야간 근무 후 07:30 취침 → 컷오프 05:30. 제한 구간 끝(06:00)으로 밀면 컷오프를 넘겨버린다. */
    @Test
    void clampMainMeal_whenCutoffIsInsideNightRestriction_doesNotOvershootCutoff() {
        LocalTime result = AiSleepMealValidator.clampMainMeal(
                LocalTime.of(2, 0), LocalTime.of(5, 30), LocalTime.of(0, 0), LocalTime.of(6, 0));
        assertThat(result).isEqualTo(LocalTime.of(5, 30));
    }

    @Test
    void clampMainMeal_beyondCutoff_isClampedToCutoff() {
        LocalTime result = AiSleepMealValidator.clampMainMeal(
                LocalTime.of(23, 0), LocalTime.of(21, 0), LocalTime.of(0, 0), LocalTime.of(6, 0));
        assertThat(result).isEqualTo(LocalTime.of(21, 0));
    }

    @Test
    void clampMainMeal_normalTime_isUnchanged() {
        LocalTime result = AiSleepMealValidator.clampMainMeal(
                LocalTime.of(12, 0), LocalTime.of(21, 0), LocalTime.of(0, 0), LocalTime.of(6, 0));
        assertThat(result).isEqualTo(LocalTime.of(12, 0));
    }
}

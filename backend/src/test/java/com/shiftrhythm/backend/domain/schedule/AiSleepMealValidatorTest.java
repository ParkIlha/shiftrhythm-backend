package com.shiftrhythm.backend.domain.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class AiSleepMealValidatorTest {

    private static final SleepWindow WIDE_NIGHT_WINDOW = new SleepWindow(LocalTime.of(9, 0), LocalTime.of(22, 0));

    private static SleepBlock block(LocalTime start, LocalTime end, int tolerance, LocalTime ankerStart, LocalTime ankerEnd) {
        return new SleepBlock(start, end, null, null, null, tolerance, ankerStart, ankerEnd);
    }

    @Test
    void clampSleep_withinToleranceAndWindow_staysUnchanged() {
        SleepBlock ruleBased = block(LocalTime.of(10, 0), LocalTime.of(17, 0), 60, null, null);
        SleepBlock proposed = block(LocalTime.of(10, 30), LocalTime.of(17, 30), 60, null, null);

        SleepBlock result = AiSleepMealValidator.clampSleep(proposed, ruleBased, WIDE_NIGHT_WINDOW);

        assertThat(result.mainSleepStart()).isEqualTo(LocalTime.of(10, 30));
        assertThat(result.mainSleepEnd()).isEqualTo(LocalTime.of(17, 30));
    }

    @Test
    void clampSleep_farBeyondTolerance_isClampedToToleranceBoundEvenThoughWithinWindow() {
        // window(09:00~22:00, 13시간) 안이라 window clamp만으로는 안 걸리지만,
        // 초안(10:00~17:00)에서 tolerance(60분)를 훨씬 넘어선 값이라 tolerance 경계로 clamp되어야 한다.
        SleepBlock ruleBased = block(LocalTime.of(10, 0), LocalTime.of(17, 0), 60, null, null);
        SleepBlock proposed = block(LocalTime.of(20, 0), LocalTime.of(21, 30), 60, null, null);

        SleepBlock result = AiSleepMealValidator.clampSleep(proposed, ruleBased, WIDE_NIGHT_WINDOW);

        assertThat(result.mainSleepStart()).isEqualTo(LocalTime.of(11, 0)); // 10:00 + 60분
        assertThat(result.mainSleepEnd()).isEqualTo(LocalTime.of(18, 0));   // 17:00 + 60분
    }

    @Test
    void clampSleep_proposedBeyondWindow_isClampedToWindowBound() {
        SleepBlock ruleBased = block(LocalTime.of(9, 30), LocalTime.of(21, 30), 600, null, null);
        SleepBlock proposed = block(LocalTime.of(8, 0), LocalTime.of(23, 0), 600, null, null);

        SleepBlock result = AiSleepMealValidator.clampSleep(proposed, ruleBased, WIDE_NIGHT_WINDOW);

        assertThat(result.mainSleepStart()).isEqualTo(WIDE_NIGHT_WINDOW.earliestSleepStart());
        assertThat(result.mainSleepEnd()).isEqualTo(WIDE_NIGHT_WINDOW.latestSleepEnd());
    }

    @Test
    void clampSleep_anchorOutsideTolerance_forcesExpansionBeyondTolerance() {
        // 앵커구간(09:30~10:30)이 tolerance 범위(11:30~12:30) 밖에 있어도 하드 제약이라 강제 포함돼야 한다.
        SleepBlock ruleBased = block(LocalTime.of(12, 0), LocalTime.of(17, 0), 30, null, null);
        SleepBlock proposed = block(LocalTime.of(12, 0), LocalTime.of(17, 0), 30, LocalTime.of(9, 30), LocalTime.of(10, 30));

        SleepBlock result = AiSleepMealValidator.clampSleep(proposed, ruleBased, WIDE_NIGHT_WINDOW);

        assertThat(result.mainSleepStart()).isEqualTo(LocalTime.of(9, 30));
        assertThat(result.mainSleepEnd()).isEqualTo(LocalTime.of(17, 0));
    }

    @Test
    void isMainSleepSuspiciouslyShort_below80Percent_isTrue() {
        SleepBlock ruleBased = block(LocalTime.of(10, 0), LocalTime.of(17, 0), 60, null, null); // 7시간
        SleepBlock proposed = block(LocalTime.of(10, 0), LocalTime.of(14, 0), 60, null, null); // 4시간

        assertThat(AiSleepMealValidator.isMainSleepSuspiciouslyShort(proposed, ruleBased)).isTrue();
    }

    @Test
    void isMainSleepSuspiciouslyShort_atOrAbove80Percent_isFalse() {
        SleepBlock ruleBased = block(LocalTime.of(10, 0), LocalTime.of(17, 0), 60, null, null); // 7시간
        SleepBlock proposed = block(LocalTime.of(10, 0), LocalTime.of(16, 0), 60, null, null); // 6시간(약 85.7%)

        assertThat(AiSleepMealValidator.isMainSleepSuspiciouslyShort(proposed, ruleBased)).isFalse();
    }

    @Test
    void clampMainMeal_withinNightRestriction_isPushedToRestrictionEnd() {
        LocalTime result = AiSleepMealValidator.clampMainMeal(
                LocalTime.of(2, 0), LocalTime.of(21, 0), LocalTime.of(0, 0), LocalTime.of(6, 0));
        assertThat(result).isEqualTo(LocalTime.of(6, 0));
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

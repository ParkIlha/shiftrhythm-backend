package com.shiftrhythm.backend.domain.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SleepBlockCalculatorTest {

    private static final int COMMUTE = 30;
    private static final int PREP = 20;
    private static final int TARGET = 420;

    private static SleepBlockContext ctx(RoutineMode mode, ShiftWindow today, ShiftWindow next, SleepBlock prev) {
        return new SleepBlockContext(mode, today, next, COMMUTE, PREP, TARGET, true, 30,
                RhythmPreference.BALANCED, prev, 1, 1, 0);
    }

    // ---- 안정 모드 ----

    @Test
    void m01_dayStable() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.DAY,
                new ShiftWindow(ShiftType.DAY, null, null),
                new ShiftWindow(ShiftType.DAY, LocalTime.of(7, 0), null),
                null)).block();
        assertThat(b.mainSleepStart()).isEqualTo(LocalTime.of(23, 10));
        assertThat(b.mainSleepEnd()).isEqualTo(LocalTime.of(6, 10));
        assertThat(b.adjustToleranceMinutes()).isEqualTo(60);
    }

    @Test
    void m02_eveningStable() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.EVENING,
                new ShiftWindow(ShiftType.EVENING, null, LocalTime.of(22, 0)),
                new ShiftWindow(ShiftType.EVENING, LocalTime.of(14, 0), null),
                null)).block();
        assertThat(b.mainSleepStart()).isEqualTo(LocalTime.of(22, 30));
        assertThat(b.mainSleepEnd()).isEqualTo(LocalTime.of(5, 30));
        assertThat(b.napMinutes()).isNull();
    }

    @Test
    void m03_nightStable() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.NIGHT,
                new ShiftWindow(ShiftType.NIGHT, null, LocalTime.of(7, 0)),
                new ShiftWindow(ShiftType.NIGHT, LocalTime.of(22, 0), null),
                null)).block();
        assertThat(b.mainSleepStart()).isEqualTo(LocalTime.of(7, 30));
        assertThat(b.mainSleepEnd()).isEqualTo(LocalTime.of(12, 0));
        assertThat(b.supplementarySleepStart()).isEqualTo(LocalTime.of(18, 40));
        assertThat(b.supplementarySleepEnd()).isEqualTo(LocalTime.of(21, 10));
        assertThat(b.napMinutes()).isNull();
    }

    // ---- 정방향 전환 ----

    @Test
    void f01_dayToEvening() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.SHIFT_TRANSITION,
                new ShiftWindow(ShiftType.DAY, null, LocalTime.of(15, 0)),
                new ShiftWindow(ShiftType.EVENING, LocalTime.of(14, 0), null),
                null)).block();
        assertThat(b.mainSleepStart()).isEqualTo(LocalTime.of(15, 30));
        assertThat(b.mainSleepEnd()).isEqualTo(LocalTime.of(22, 30));
        assertThat(b.ankerBlockStart()).isNull();
    }

    @Test
    void f02_eveningToNight() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.SHIFT_TRANSITION,
                new ShiftWindow(ShiftType.EVENING, null, LocalTime.of(23, 0)),
                new ShiftWindow(ShiftType.NIGHT, LocalTime.of(22, 0), null),
                null)).block();
        assertThat(b.mainSleepStart()).isEqualTo(LocalTime.of(23, 30));
        assertThat(b.mainSleepEnd()).isEqualTo(LocalTime.of(6, 30));
    }

    @Test
    void f03_dayToNight_windowShorterThanTarget() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.SHIFT_TRANSITION,
                new ShiftWindow(ShiftType.DAY, null, LocalTime.of(15, 0)),
                new ShiftWindow(ShiftType.NIGHT, LocalTime.of(22, 0), null),
                null)).block();
        assertThat(b.mainSleepStart()).isEqualTo(LocalTime.of(15, 30));
        assertThat(b.mainSleepEnd()).isEqualTo(LocalTime.of(21, 10)); // windowEnd, 목표에 못 미침
    }

    // ---- 역방향 전환 ----

    @Test
    void b01_eveningToDay_deadlineAnchored() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.SHIFT_TRANSITION,
                new ShiftWindow(ShiftType.EVENING, null, LocalTime.of(23, 0)),
                new ShiftWindow(ShiftType.DAY, LocalTime.of(7, 0), null),
                null)).block();
        assertThat(b.mainSleepStart()).isEqualTo(LocalTime.of(23, 30));
        assertThat(b.mainSleepEnd()).isEqualTo(LocalTime.of(6, 10));
    }

    @Test
    void b02_nightToEvening_shortageShrinksSupplementaryFirst() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.SHIFT_TRANSITION,
                new ShiftWindow(ShiftType.NIGHT, null, LocalTime.of(7, 0)),
                new ShiftWindow(ShiftType.EVENING, LocalTime.of(15, 0), null),
                null)).block();
        assertThat(b.mainSleepStart()).isEqualTo(LocalTime.of(7, 30));
        assertThat(b.mainSleepEnd()).isEqualTo(LocalTime.of(12, 0));
        assertThat(b.supplementarySleepStart()).isEqualTo(LocalTime.of(12, 0));
        assertThat(b.supplementarySleepEnd()).isEqualTo(LocalTime.of(14, 10));
        assertThat(b.napMinutes()).isEqualTo(20);
    }

    @Test
    void b03_nightToDay_isInvalid() {
        SleepBlockContext invalid = ctx(RoutineMode.SHIFT_TRANSITION,
                new ShiftWindow(ShiftType.NIGHT, null, LocalTime.of(7, 0)),
                new ShiftWindow(ShiftType.DAY, LocalTime.of(9, 0), null),
                null);
        assertThatThrownBy(() -> SleepBlockCalculator.calculate(invalid))
                .isInstanceOf(InvalidShiftTransitionException.class);
    }

    // ---- 휴무: 리듬 유지 ----

    @Test
    void o01_offMaintainDay_noAnchorNeeded() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.OFF_RHYTHM_MAINTAIN,
                new ShiftWindow(ShiftType.OFF, null, null),
                new ShiftWindow(ShiftType.DAY, LocalTime.of(7, 0), null),
                null)).block();
        assertThat(b.mainSleepStart()).isEqualTo(LocalTime.of(23, 10));
        assertThat(b.mainSleepEnd()).isEqualTo(LocalTime.of(6, 10));
        assertThat(b.ankerBlockStart()).isNull();
    }

    @Test
    void o02_offMaintainEvening_usesAnchor() {
        SleepBlock prev = new SleepBlock(LocalTime.of(22, 30), LocalTime.of(5, 30), null, null, null, 60, null, null);
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.OFF_RHYTHM_MAINTAIN,
                new ShiftWindow(ShiftType.OFF, null, null),
                new ShiftWindow(ShiftType.EVENING, LocalTime.of(15, 0), null),
                prev)).block();
        assertThat(b.mainSleepStart()).isEqualTo(LocalTime.of(22, 30));
        assertThat(b.mainSleepEnd()).isEqualTo(LocalTime.of(3, 30));
        assertThat(b.ankerBlockStart()).isEqualTo(LocalTime.of(22, 30));
    }

    @Test
    void o02b_offMaintainEvening_noPrevFallsBack() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.OFF_RHYTHM_MAINTAIN,
                new ShiftWindow(ShiftType.OFF, null, null),
                new ShiftWindow(ShiftType.EVENING, LocalTime.of(15, 0), null),
                null)).block();
        assertThat(b.ankerBlockStart()).isNull();
        assertThat(b.mainSleepEnd()).isEqualTo(b.mainSleepStart().plusMinutes(TARGET));
    }

    // ---- 휴무: 리듬 전환 (단일 휴무일) ----

    @Test
    void s01_nightToEvening_offShift_backwardMove() {
        SleepBlock prev = new SleepBlock(LocalTime.of(7, 30), LocalTime.of(12, 0), null, null, null, 60, null, null);
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.OFF_RHYTHM_SHIFT,
                new ShiftWindow(ShiftType.OFF, null, null),
                new ShiftWindow(ShiftType.EVENING, LocalTime.of(15, 0), null),
                prev)).block();
        assertThat(b.mainSleepStart()).isEqualTo(LocalTime.of(7, 10));
        assertThat(b.mainSleepEnd()).isEqualTo(LocalTime.of(14, 10));
    }

    @Test
    void s03_dayToEvening_offShift_forwardMove() {
        SleepBlock prev = new SleepBlock(LocalTime.of(23, 10), LocalTime.of(6, 10), null, null, null, 60, null, null);
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.OFF_RHYTHM_SHIFT,
                new ShiftWindow(ShiftType.OFF, null, null),
                new ShiftWindow(ShiftType.EVENING, LocalTime.of(15, 0), null),
                prev)).block();
        assertThat(b.mainSleepStart()).isEqualTo(LocalTime.of(7, 10));
        assertThat(b.mainSleepEnd()).isEqualTo(LocalTime.of(14, 10));
    }

    // ---- 휴무: 회복 ----

    @Test
    void offRecovery_firstDay_addsBonusUpTo90() {
        SleepBlock prev = new SleepBlock(LocalTime.of(23, 0), LocalTime.of(6, 0), null, null, null, 60, null, null);
        SleepBlockContext recoveryCtx = new SleepBlockContext(RoutineMode.OFF_RECOVERY,
                new ShiftWindow(ShiftType.OFF, null, null),
                new ShiftWindow(ShiftType.DAY, LocalTime.of(7, 0), null),
                COMMUTE, PREP, TARGET, true, 30, RhythmPreference.BALANCED, prev,
                1, 2, 120); // 누적부족 120분 -> 보너스는 90분 cap
        SleepBlock b = SleepBlockCalculator.calculate(recoveryCtx).block();
        assertThat(b.adjustToleranceMinutes()).isEqualTo(90);
        assertThat(SleepTimeMath.minutesBetween(b.mainSleepStart(), b.mainSleepEnd())).isEqualTo(TARGET + 90);
    }

    @Test
    void offRecovery_lastDay_targetsNextShift() {
        SleepBlockContext recoveryCtx = new SleepBlockContext(RoutineMode.OFF_RECOVERY,
                new ShiftWindow(ShiftType.OFF, null, null),
                new ShiftWindow(ShiftType.DAY, LocalTime.of(7, 0), null),
                COMMUTE, PREP, TARGET, true, 30, RhythmPreference.BALANCED, null,
                2, 2, 0);
        SleepBlock b = SleepBlockCalculator.calculate(recoveryCtx).block();
        assertThat(b.mainSleepStart()).isEqualTo(LocalTime.of(23, 10));
        assertThat(b.mainSleepEnd()).isEqualTo(LocalTime.of(6, 10));
    }
}

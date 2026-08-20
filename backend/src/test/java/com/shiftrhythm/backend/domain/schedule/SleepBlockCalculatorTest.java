package com.shiftrhythm.backend.domain.schedule;

import com.shiftrhythm.backend.domain.schedule.policy.*;
import com.shiftrhythm.backend.domain.schedule.util.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SleepBlockCalculatorTest {

    private static final int COMMUTE = 30;
    private static final int PREP = 20;
    private static final int TARGET = 420;
    private static final LocalDate D = LocalDate.of(2024, 1, 1);

    private static LocalDateTime t(int h, int m) {
        return LocalDateTime.of(D, LocalTime.of(h, m));
    }

    /** D+1 기준 시각 — 오늘 근무 종료~다음 근무 시작이 자정을 넘기는 케이스(next가 다음 날짜)용. */
    private static LocalDateTime t1(int h, int m) {
        return LocalDateTime.of(D.plusDays(1), LocalTime.of(h, m));
    }

    private static SleepBlockContext ctx(RoutineMode mode, ShiftWindow today, ShiftWindow next, SleepBlock prev) {
        return new SleepBlockContext(mode, D, today, next, COMMUTE, PREP, TARGET, true, 30,
                RhythmPreference.BALANCED, prev, 1, 1, 0);
    }

    // ---- 안정 모드 ----

    @Test
    void m01_dayStable() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.DAY,
                new ShiftWindow(ShiftType.DAY, null, null),
                new ShiftWindow(ShiftType.DAY, t(7, 0), null),
                null)).block();
        assertThat(b.mainSleepStart().toLocalTime()).isEqualTo(LocalTime.of(23, 10));
        assertThat(b.mainSleepEnd().toLocalTime()).isEqualTo(LocalTime.of(6, 10));
        assertThat(b.adjustToleranceMinutes()).isEqualTo(60);
    }

    @Test
    void m02_eveningStable() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.EVENING,
                new ShiftWindow(ShiftType.EVENING, null, t(22, 0)),
                new ShiftWindow(ShiftType.EVENING, t1(14, 0), null),
                null)).block();
        assertThat(b.mainSleepStart().toLocalTime()).isEqualTo(LocalTime.of(22, 30));
        assertThat(b.mainSleepEnd().toLocalTime()).isEqualTo(LocalTime.of(5, 30));
        assertThat(b.napMinutes()).isNull();
    }

    /**
     * 회귀 테스트: NIGHT->NIGHT stableNight에서 주수면/보조수면이 실제 근무 시각(자정을 넘겨 실제
     * 날짜가 다른)과 겹치면 안 된다. today 근무는 D 22:00~D+1 06:00, next 근무는 D+1 22:00~D+2 06:00
     * 처럼 실제 날짜를 가진 ShiftWindow(RoutineComputationService.toWindow가 만드는 것과 동일한 형태)로
     * 계산하면, 수면은 반드시 D+1 06:xx ~ D+1 21:xx 구간(오늘 근무 종료 이후 ~ 다음 근무 시작 이전) 안에만
     * 있어야 한다 — 실제 문제(백엔드 설계 버그)로 보조수면이 근무시간(D 22:00~D+1 06:00)과 겹쳐 보이던
     * 케이스의 재현/방지용.
     */
    @Test
    void regression_nightToNight_sleepNeverOverlapsRealShiftWindow() {
        LocalDateTime todayShiftStart = LocalDateTime.of(D, LocalTime.of(22, 0));
        LocalDateTime todayShiftEnd = LocalDateTime.of(D.plusDays(1), LocalTime.of(6, 0));
        LocalDateTime nextShiftStart = LocalDateTime.of(D.plusDays(1), LocalTime.of(22, 0));
        LocalDateTime nextShiftEnd = LocalDateTime.of(D.plusDays(2), LocalTime.of(6, 0));

        SleepBlockContext context = new SleepBlockContext(RoutineMode.NIGHT, D,
                new ShiftWindow(ShiftType.NIGHT, todayShiftStart, todayShiftEnd),
                new ShiftWindow(ShiftType.NIGHT, nextShiftStart, nextShiftEnd),
                COMMUTE, PREP, TARGET, true, 30, RhythmPreference.BALANCED, null, 1, 1, 0);

        SleepBlock b = SleepBlockCalculator.calculate(context).block();

        // 주수면/보조수면 모두 오늘 근무 종료 이후, 다음 근무 시작 이전이어야 한다.
        assertThat(b.mainSleepStart()).isAfterOrEqualTo(todayShiftEnd);
        assertThat(b.mainSleepEnd()).isBeforeOrEqualTo(nextShiftStart);
        assertThat(b.supplementarySleepStart()).isAfterOrEqualTo(todayShiftEnd);
        assertThat(b.supplementarySleepEnd()).isBeforeOrEqualTo(nextShiftStart);

        // 명시적으로 오늘/다음 근무 실구간과 겹치지 않는지도 확인
        assertThat(b.mainSleepStart()).isAfterOrEqualTo(todayShiftEnd);
        assertThat(b.supplementarySleepEnd()).isBeforeOrEqualTo(nextShiftStart);
    }

    @Test
    void m03_nightStable() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.NIGHT,
                new ShiftWindow(ShiftType.NIGHT, null, t(7, 0)),
                new ShiftWindow(ShiftType.NIGHT, t(22, 0), null),
                null)).block();
        assertThat(b.mainSleepStart().toLocalTime()).isEqualTo(LocalTime.of(7, 30));
        assertThat(b.mainSleepEnd().toLocalTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(b.supplementarySleepStart().toLocalTime()).isEqualTo(LocalTime.of(18, 40));
        assertThat(b.supplementarySleepEnd().toLocalTime()).isEqualTo(LocalTime.of(21, 10));
        assertThat(b.napMinutes()).isNull();
    }

    // ---- 정방향 전환 ----

    @Test
    void f01_dayToEvening() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.SHIFT_TRANSITION,
                new ShiftWindow(ShiftType.DAY, null, t(15, 0)),
                new ShiftWindow(ShiftType.EVENING, t1(14, 0), null),
                null)).block();
        assertThat(b.mainSleepStart().toLocalTime()).isEqualTo(LocalTime.of(15, 30));
        assertThat(b.mainSleepEnd().toLocalTime()).isEqualTo(LocalTime.of(22, 30));
        assertThat(b.ankerBlockStart()).isNull();
    }

    @Test
    void f02_eveningToNight() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.SHIFT_TRANSITION,
                new ShiftWindow(ShiftType.EVENING, null, t(23, 0)),
                new ShiftWindow(ShiftType.NIGHT, t1(22, 0), null),
                null)).block();
        assertThat(b.mainSleepStart().toLocalTime()).isEqualTo(LocalTime.of(23, 30));
        assertThat(b.mainSleepEnd().toLocalTime()).isEqualTo(LocalTime.of(6, 30));
    }

    @Test
    void f03_dayToNight_windowShorterThanTarget() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.SHIFT_TRANSITION,
                new ShiftWindow(ShiftType.DAY, null, t(15, 0)),
                new ShiftWindow(ShiftType.NIGHT, t(22, 0), null),
                null)).block();
        assertThat(b.mainSleepStart().toLocalTime()).isEqualTo(LocalTime.of(15, 30));
        assertThat(b.mainSleepEnd().toLocalTime()).isEqualTo(LocalTime.of(21, 10)); // windowEnd, 목표에 못 미침
    }

    // ---- 역방향 전환 ----

    @Test
    void b01_eveningToDay_deadlineAnchored() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.SHIFT_TRANSITION,
                new ShiftWindow(ShiftType.EVENING, null, t(23, 0)),
                new ShiftWindow(ShiftType.DAY, t1(7, 0), null),
                null)).block();
        assertThat(b.mainSleepStart().toLocalTime()).isEqualTo(LocalTime.of(23, 30));
        assertThat(b.mainSleepEnd().toLocalTime()).isEqualTo(LocalTime.of(6, 10));
    }

    @Test
    void b02_nightToEvening_shortageShrinksSupplementaryFirst() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.SHIFT_TRANSITION,
                new ShiftWindow(ShiftType.NIGHT, null, t(7, 0)),
                new ShiftWindow(ShiftType.EVENING, t(15, 0), null),
                null)).block();
        assertThat(b.mainSleepStart().toLocalTime()).isEqualTo(LocalTime.of(7, 30));
        assertThat(b.mainSleepEnd().toLocalTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(b.supplementarySleepStart().toLocalTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(b.supplementarySleepEnd().toLocalTime()).isEqualTo(LocalTime.of(14, 10));
        assertThat(b.napMinutes()).isEqualTo(20);
    }

    @Test
    void b03_nightToDay_isInvalid() {
        SleepBlockContext invalid = ctx(RoutineMode.SHIFT_TRANSITION,
                new ShiftWindow(ShiftType.NIGHT, null, t(7, 0)),
                new ShiftWindow(ShiftType.DAY, t(9, 0), null),
                null);
        assertThatThrownBy(() -> SleepBlockCalculator.calculate(invalid))
                .isInstanceOf(InvalidShiftTransitionException.class);
    }

    // ---- 휴무: 리듬 유지 ----

    @Test
    void o01_offMaintainDay_noAnchorNeeded() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.OFF_RHYTHM_MAINTAIN,
                new ShiftWindow(ShiftType.OFF, null, null),
                new ShiftWindow(ShiftType.DAY, t(7, 0), null),
                null)).block();
        assertThat(b.mainSleepStart().toLocalTime()).isEqualTo(LocalTime.of(23, 10));
        assertThat(b.mainSleepEnd().toLocalTime()).isEqualTo(LocalTime.of(6, 10));
        assertThat(b.ankerBlockStart()).isNull();
    }

    @Test
    void o02_offMaintainEvening_usesAnchor() {
        SleepBlock prev = new SleepBlock(t(22, 30), t(5, 30), null, null, null, 60, null, null);
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.OFF_RHYTHM_MAINTAIN,
                new ShiftWindow(ShiftType.OFF, null, null),
                new ShiftWindow(ShiftType.EVENING, t(15, 0), null),
                prev)).block();
        assertThat(b.mainSleepStart().toLocalTime()).isEqualTo(LocalTime.of(22, 30));
        assertThat(b.mainSleepEnd().toLocalTime()).isEqualTo(LocalTime.of(3, 30));
        assertThat(b.ankerBlockStart().toLocalTime()).isEqualTo(LocalTime.of(22, 30));
    }

    @Test
    void o02b_offMaintainEvening_noPrevFallsBack() {
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.OFF_RHYTHM_MAINTAIN,
                new ShiftWindow(ShiftType.OFF, null, null),
                new ShiftWindow(ShiftType.EVENING, t(15, 0), null),
                null)).block();
        assertThat(b.ankerBlockStart()).isNull();
        assertThat(b.mainSleepEnd()).isEqualTo(b.mainSleepStart().plusMinutes(TARGET));
    }

    // ---- 휴무: 리듬 전환 (단일 휴무일) ----

    @Test
    void s01_nightToEvening_offShift_backwardMove() {
        SleepBlock prev = new SleepBlock(t(7, 30), t(12, 0), null, null, null, 60, null, null);
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.OFF_RHYTHM_SHIFT,
                new ShiftWindow(ShiftType.OFF, null, null),
                new ShiftWindow(ShiftType.EVENING, t(15, 0), null),
                prev)).block();
        assertThat(b.mainSleepStart().toLocalTime()).isEqualTo(LocalTime.of(7, 10));
        assertThat(b.mainSleepEnd().toLocalTime()).isEqualTo(LocalTime.of(14, 10));
    }

    @Test
    void s03_dayToEvening_offShift_forwardMove() {
        SleepBlock prev = new SleepBlock(t(23, 10), t(6, 10), null, null, null, 60, null, null);
        SleepBlock b = SleepBlockCalculator.calculate(ctx(RoutineMode.OFF_RHYTHM_SHIFT,
                new ShiftWindow(ShiftType.OFF, null, null),
                new ShiftWindow(ShiftType.EVENING, t(15, 0), null),
                prev)).block();
        assertThat(b.mainSleepStart().toLocalTime()).isEqualTo(LocalTime.of(7, 10));
        assertThat(b.mainSleepEnd().toLocalTime()).isEqualTo(LocalTime.of(14, 10));
    }

    // ---- 휴무: 회복 ----

    @Test
    void offRecovery_firstDay_addsBonusUpTo90() {
        SleepBlock prev = new SleepBlock(t(23, 0), t(6, 0), null, null, null, 60, null, null);
        SleepBlockContext recoveryCtx = new SleepBlockContext(RoutineMode.OFF_RECOVERY, D,
                new ShiftWindow(ShiftType.OFF, null, null),
                new ShiftWindow(ShiftType.DAY, t(7, 0), null),
                COMMUTE, PREP, TARGET, true, 30, RhythmPreference.BALANCED, prev,
                1, 2, 120); // 누적부족 120분 -> 보너스는 90분 cap
        SleepBlock b = SleepBlockCalculator.calculate(recoveryCtx).block();
        assertThat(b.adjustToleranceMinutes()).isEqualTo(90);
        assertThat(SleepTimeMath.minutesBetween(b.mainSleepStart(), b.mainSleepEnd())).isEqualTo(TARGET + 90);
    }

    @Test
    void offRecovery_lastDay_targetsNextShift() {
        SleepBlockContext recoveryCtx = new SleepBlockContext(RoutineMode.OFF_RECOVERY, D,
                new ShiftWindow(ShiftType.OFF, null, null),
                new ShiftWindow(ShiftType.DAY, t(7, 0), null),
                COMMUTE, PREP, TARGET, true, 30, RhythmPreference.BALANCED, null,
                2, 2, 0);
        SleepBlock b = SleepBlockCalculator.calculate(recoveryCtx).block();
        assertThat(b.mainSleepStart().toLocalTime()).isEqualTo(LocalTime.of(23, 10));
        assertThat(b.mainSleepEnd().toLocalTime()).isEqualTo(LocalTime.of(6, 10));
    }
}

package com.shiftrhythm.backend.domain.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModeClassifierTest {

    private static final LocalDate D0 = LocalDate.of(2026, 8, 10);

    private static DaySchedule d(int offset, ShiftType type) {
        return new DaySchedule(D0.plusDays(offset), type);
    }

    @Test
    void dayFollowedByDay_staysDay() {
        List<DaySchedule> schedule = List.of(d(0, ShiftType.DAY), d(1, ShiftType.DAY));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.DAY);
    }

    @Test
    void eveningFollowedByEvening_staysEvening() {
        List<DaySchedule> schedule = List.of(d(0, ShiftType.EVENING), d(1, ShiftType.EVENING));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.EVENING);
    }

    @Test
    void nightFollowedByNight_staysNight() {
        List<DaySchedule> schedule = List.of(d(0, ShiftType.NIGHT), d(1, ShiftType.NIGHT));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.NIGHT);
    }

    @Test
    void differentShiftTomorrow_isShiftTransition() {
        List<DaySchedule> schedule = List.of(d(0, ShiftType.DAY), d(1, ShiftType.EVENING));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.SHIFT_TRANSITION);
    }

    @Test
    void twoConsecutiveOffDays_isOffRecovery() {
        List<DaySchedule> schedule = List.of(d(0, ShiftType.OFF), d(1, ShiftType.OFF));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.OFF_RECOVERY);
    }

    @Test
    void singleOffDay_sameShiftBeforeAndAfter_isMaintain() {
        List<DaySchedule> schedule = List.of(d(-1, ShiftType.DAY), d(0, ShiftType.OFF), d(1, ShiftType.DAY));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.OFF_RHYTHM_MAINTAIN);
    }

    @Test
    void singleOffDay_differentShiftBeforeAndAfter_isShift() {
        List<DaySchedule> schedule = List.of(d(-1, ShiftType.NIGHT), d(0, ShiftType.OFF), d(1, ShiftType.EVENING));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.OFF_RHYTHM_SHIFT);
    }

    @Test
    void singleOffDay_dayToEvening_isShift() {
        List<DaySchedule> schedule = List.of(d(-1, ShiftType.DAY), d(0, ShiftType.OFF), d(1, ShiftType.EVENING));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.OFF_RHYTHM_SHIFT);
    }

    @Test
    void unknownPreviousShift_fallsBackToMaintain() {
        // 근무표 맨 앞의 단일 OFF — 휴무 전 근무유형을 알 수 없음
        List<DaySchedule> schedule = List.of(d(0, ShiftType.OFF), d(1, ShiftType.DAY));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.OFF_RHYTHM_MAINTAIN);
    }

    @Test
    void offStreakOf_computesIndexAndTotal() {
        List<DaySchedule> schedule = List.of(
                d(0, ShiftType.NIGHT), d(1, ShiftType.OFF), d(2, ShiftType.OFF), d(3, ShiftType.OFF), d(4, ShiftType.NIGHT)
        );
        assertThat(ModeClassifier.offStreakOf(schedule, D0.plusDays(1))).isEqualTo(new ModeClassifier.OffStreak(1, 3));
        assertThat(ModeClassifier.offStreakOf(schedule, D0.plusDays(2))).isEqualTo(new ModeClassifier.OffStreak(2, 3));
        assertThat(ModeClassifier.offStreakOf(schedule, D0.plusDays(3))).isEqualTo(new ModeClassifier.OffStreak(3, 3));
        assertThat(ModeClassifier.offStreakOf(schedule, D0)).isEqualTo(new ModeClassifier.OffStreak(0, 0));
    }
}

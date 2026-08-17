package com.shiftrhythm.backend.domain.schedule;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ModeClassifierTest {

    private static final LocalDate D0 = LocalDate.of(2026, 8, 10);

    private static DaySchedule d(int offset, ShiftType type) {
        return new DaySchedule(D0.plusDays(offset), type);
    }

    private static RoutineMode stableModeOf(ShiftType type) {
        return switch (type) {
            case DAY -> RoutineMode.DAY;
            case EVENING -> RoutineMode.EVENING;
            case NIGHT -> RoutineMode.NIGHT;
            case OFF -> throw new IllegalArgumentException();
        };
    }

    // ── A. 근무일 - 오늘/내일 모두 근무 (케이스 1~9) ──────────────────────

    static Stream<Arguments> workToWorkCases() {
        return Stream.of(
                Arguments.of(ShiftType.DAY, ShiftType.DAY, RoutineMode.DAY),
                Arguments.of(ShiftType.EVENING, ShiftType.EVENING, RoutineMode.EVENING),
                Arguments.of(ShiftType.NIGHT, ShiftType.NIGHT, RoutineMode.NIGHT),
                Arguments.of(ShiftType.DAY, ShiftType.EVENING, RoutineMode.SHIFT_TRANSITION),
                Arguments.of(ShiftType.DAY, ShiftType.NIGHT, RoutineMode.SHIFT_TRANSITION),
                Arguments.of(ShiftType.EVENING, ShiftType.DAY, RoutineMode.SHIFT_TRANSITION),
                Arguments.of(ShiftType.EVENING, ShiftType.NIGHT, RoutineMode.SHIFT_TRANSITION),
                Arguments.of(ShiftType.NIGHT, ShiftType.DAY, RoutineMode.SHIFT_TRANSITION),
                Arguments.of(ShiftType.NIGHT, ShiftType.EVENING, RoutineMode.SHIFT_TRANSITION)
        );
    }

    @ParameterizedTest(name = "{0} -> {1} = {2}")
    @MethodSource("workToWorkCases")
    void workDayFollowedByWorkDay(ShiftType today, ShiftType tomorrow, RoutineMode expected) {
        List<DaySchedule> schedule = List.of(d(0, today), d(1, tomorrow));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(expected);
    }

    // ── B. 근무일 - 내일부터 휴무, 오늘 유형 유지 (케이스 10~12) ──────────

    @ParameterizedTest(name = "{0} 마지막 근무일 유지")
    @org.junit.jupiter.params.provider.EnumSource(value = ShiftType.class, names = {"DAY", "EVENING", "NIGHT"})
    void lastWorkDayBeforeOff_staysSameType(ShiftType today) {
        List<DaySchedule> schedule = List.of(d(0, today), d(1, ShiftType.OFF));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(stableModeOf(today));
    }

    // ── C. 휴무 1일 블록 - 휴무 전/후 유형 비교 (케이스 13~21) ────────────

    static Stream<Arguments> singleOffDayCases() {
        return Stream.of(
                Arguments.of(ShiftType.DAY, ShiftType.DAY, RoutineMode.OFF_RHYTHM_MAINTAIN),
                Arguments.of(ShiftType.EVENING, ShiftType.EVENING, RoutineMode.OFF_RHYTHM_MAINTAIN),
                Arguments.of(ShiftType.NIGHT, ShiftType.NIGHT, RoutineMode.OFF_RHYTHM_MAINTAIN),
                Arguments.of(ShiftType.DAY, ShiftType.EVENING, RoutineMode.OFF_RHYTHM_SHIFT),
                Arguments.of(ShiftType.DAY, ShiftType.NIGHT, RoutineMode.OFF_RHYTHM_SHIFT),
                Arguments.of(ShiftType.EVENING, ShiftType.DAY, RoutineMode.OFF_RHYTHM_SHIFT),
                Arguments.of(ShiftType.EVENING, ShiftType.NIGHT, RoutineMode.OFF_RHYTHM_SHIFT),
                Arguments.of(ShiftType.NIGHT, ShiftType.DAY, RoutineMode.OFF_RHYTHM_SHIFT),
                Arguments.of(ShiftType.NIGHT, ShiftType.EVENING, RoutineMode.OFF_RHYTHM_SHIFT)
        );
    }

    @ParameterizedTest(name = "휴무 전 {0} / 휴무 후 {1} = {2}")
    @MethodSource("singleOffDayCases")
    void singleOffDay_comparesBeforeAndAfter(ShiftType prev, ShiftType next, RoutineMode expected) {
        List<DaySchedule> schedule = List.of(d(-1, prev), d(0, ShiftType.OFF), d(1, next));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(expected);
    }

    // ── D. 휴무 1일 블록 - fallback (unknown) (케이스 22~28) ──────────────

    @ParameterizedTest(name = "휴무 전 unknown, 휴무 후 {0} = 리듬유지")
    @org.junit.jupiter.params.provider.EnumSource(value = ShiftType.class, names = {"DAY", "EVENING", "NIGHT"})
    void unknownPrev_fallsBackToMaintain(ShiftType next) {
        // 근무표 맨 앞의 단일 OFF — 휴무 전 근무유형을 알 수 없음
        List<DaySchedule> schedule = List.of(d(0, ShiftType.OFF), d(1, next));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.OFF_RHYTHM_MAINTAIN);
    }

    @ParameterizedTest(name = "휴무 전 {0}, 휴무 후 unknown = 리듬유지")
    @org.junit.jupiter.params.provider.EnumSource(value = ShiftType.class, names = {"DAY", "EVENING", "NIGHT"})
    void unknownNext_fallsBackToMaintain(ShiftType prev) {
        // 근무표 맨 뒤의 단일 OFF — 휴무 후 근무유형을 알 수 없음
        List<DaySchedule> schedule = List.of(d(-1, prev), d(0, ShiftType.OFF));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.OFF_RHYTHM_MAINTAIN);
    }

    @Test
    void unknownPrevAndNext_doubleFallsBackToMaintain() {
        // 근무표가 단 하루(OFF)뿐 — 휴무 전/후 모두 알 수 없음
        List<DaySchedule> schedule = List.of(d(0, ShiftType.OFF));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.OFF_RHYTHM_MAINTAIN);
    }

    // ── E. 휴무 회복 모드 (2일 이상 블록) (케이스 29~36) ──────────────────

    private static List<DaySchedule> offBlock(int total, ShiftType prev, ShiftType next) {
        List<DaySchedule> schedule = new java.util.ArrayList<>();
        schedule.add(d(-1, prev));
        for (int i = 0; i < total; i++) {
            schedule.add(d(i, ShiftType.OFF));
        }
        schedule.add(d(total, next));
        return schedule;
    }

    @Test
    void offBlockOfTwo_bothDaysAreRecovery() {
        List<DaySchedule> schedule = offBlock(2, ShiftType.NIGHT, ShiftType.DAY);
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.OFF_RECOVERY); // 1일차
        assertThat(ModeClassifier.classify(schedule, D0.plusDays(1))).isEqualTo(RoutineMode.OFF_RECOVERY); // 2일차(마지막날, 버그 수정 검증)
    }

    @Test
    void offBlockOfThree_allDaysAreRecovery() {
        List<DaySchedule> schedule = offBlock(3, ShiftType.NIGHT, ShiftType.DAY);
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.OFF_RECOVERY); // 1일차
        assertThat(ModeClassifier.classify(schedule, D0.plusDays(1))).isEqualTo(RoutineMode.OFF_RECOVERY); // 2일차
        assertThat(ModeClassifier.classify(schedule, D0.plusDays(2))).isEqualTo(RoutineMode.OFF_RECOVERY); // 3일차(마지막날, 버그 수정 검증)
    }

    @Test
    void offBlockOfFour_middleAndLastAreRecovery() {
        List<DaySchedule> schedule = offBlock(4, ShiftType.NIGHT, ShiftType.DAY);
        assertThat(ModeClassifier.classify(schedule, D0.plusDays(1))).isEqualTo(RoutineMode.OFF_RECOVERY); // 중간
        assertThat(ModeClassifier.classify(schedule, D0.plusDays(3))).isEqualTo(RoutineMode.OFF_RECOVERY); // 마지막날
    }

    @Test
    void offBlockLongerThanFour_recoveryTakesPriorityOverTypeComparison() {
        // 휴무전=NIGHT / 휴무후=DAY로 유형이 달라도(원래 OFF_RHYTHM_SHIFT 대상) 블록 길이 >=2면 RECOVERY가 우선한다
        List<DaySchedule> schedule = offBlock(5, ShiftType.NIGHT, ShiftType.DAY);
        for (int i = 0; i < 5; i++) {
            assertThat(ModeClassifier.classify(schedule, D0.plusDays(i))).isEqualTo(RoutineMode.OFF_RECOVERY);
        }
    }

    // ── F. 경계/구조 케이스 (케이스 37~39) ────────────────────────────────

    @Test
    void offWorkOff_blocksAreClassifiedIndependently() {
        // 휴무(2일) - 근무(1일) - 휴무(2일): 근무일로 블록이 분리되어 각 휴무 블록이 독립적으로 판정돼야 한다
        List<DaySchedule> schedule = List.of(
                d(0, ShiftType.OFF), d(1, ShiftType.OFF),
                d(2, ShiftType.DAY),
                d(3, ShiftType.OFF), d(4, ShiftType.OFF)
        );
        assertThat(ModeClassifier.offStreakOf(schedule, D0)).isEqualTo(new ModeClassifier.OffStreak(1, 2));
        assertThat(ModeClassifier.offStreakOf(schedule, D0.plusDays(1))).isEqualTo(new ModeClassifier.OffStreak(2, 2));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.OFF_RECOVERY);
        assertThat(ModeClassifier.classify(schedule, D0.plusDays(1))).isEqualTo(RoutineMode.OFF_RECOVERY);

        assertThat(ModeClassifier.classify(schedule, D0.plusDays(2))).isEqualTo(RoutineMode.DAY);

        assertThat(ModeClassifier.offStreakOf(schedule, D0.plusDays(3))).isEqualTo(new ModeClassifier.OffStreak(1, 2));
        assertThat(ModeClassifier.offStreakOf(schedule, D0.plusDays(4))).isEqualTo(new ModeClassifier.OffStreak(2, 2));
        assertThat(ModeClassifier.classify(schedule, D0.plusDays(3))).isEqualTo(RoutineMode.OFF_RECOVERY);
        assertThat(ModeClassifier.classify(schedule, D0.plusDays(4))).isEqualTo(RoutineMode.OFF_RECOVERY);
    }

    @Test
    void allOffSchedule_everyDayIsRecovery() {
        // 스케줄 전체가 휴무로만 구성 — total>=2면 유형비교 분기 자체에 도달하지 않는다
        List<DaySchedule> schedule = List.of(d(0, ShiftType.OFF), d(1, ShiftType.OFF), d(2, ShiftType.OFF));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.OFF_RECOVERY);
        assertThat(ModeClassifier.classify(schedule, D0.plusDays(1))).isEqualTo(RoutineMode.OFF_RECOVERY);
        assertThat(ModeClassifier.classify(schedule, D0.plusDays(2))).isEqualTo(RoutineMode.OFF_RECOVERY);
    }

    @Test
    void allWorkSchedule_neverProducesOffMode() {
        // 스케줄 전체가 근무로만 구성 — 휴무 분기 전체에 도달하지 않는다
        List<DaySchedule> schedule = List.of(d(0, ShiftType.DAY), d(1, ShiftType.EVENING), d(2, ShiftType.NIGHT));
        assertThat(ModeClassifier.classify(schedule, D0)).isEqualTo(RoutineMode.SHIFT_TRANSITION);
        assertThat(ModeClassifier.classify(schedule, D0.plusDays(1))).isEqualTo(RoutineMode.SHIFT_TRANSITION);
        // 마지막 날은 내일 데이터가 없어(tomorrow == null) 오늘 유형을 유지한다
        assertThat(ModeClassifier.classify(schedule, D0.plusDays(2))).isEqualTo(RoutineMode.NIGHT);
    }

    // ── offStreakOf 자체 검증 ──────────────────────────────────────────

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

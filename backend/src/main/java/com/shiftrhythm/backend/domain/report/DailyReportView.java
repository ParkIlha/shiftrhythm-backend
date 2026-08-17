package com.shiftrhythm.backend.domain.report;

import com.shiftrhythm.backend.domain.routine.ReplanReason;
import com.shiftrhythm.backend.domain.schedule.RoutineMode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record DailyReportView(
        LocalDate date,
        PlanVsActual sleep,
        List<ReplanLogEntry> replanLog,
        CheckInView checkIn
) {
    /** plan = version 1(최초 규칙 기반 초안), actual = 현재 is_current로 남아있는 최종 확정본. */
    public record PlanVsActual(RoutineMode plannedMode, LocalTime plannedSleepStart, LocalTime plannedSleepEnd, long plannedSleepMinutes,
                                RoutineMode actualMode, LocalTime actualSleepStart, LocalTime actualSleepEnd, long actualSleepMinutes) {
    }

    public record ReplanLogEntry(int version, ReplanReason reason, String aiReason,
                                  LocalTime sleepStartBefore, LocalTime sleepStartAfter,
                                  LocalTime sleepEndBefore, LocalTime sleepEndAfter) {
    }

    public record CheckInView(Integer conditionScore, Integer sleepSatisfaction,
                               Integer sleepLatencyMinutes, Integer nightHungerScore) {
    }
}

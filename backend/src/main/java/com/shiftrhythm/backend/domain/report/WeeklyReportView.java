package com.shiftrhythm.backend.domain.report;

import com.shiftrhythm.backend.domain.schedule.RoutineMode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

public record WeeklyReportView(
        LocalDate from,
        LocalDate to,
        long totalSleepMinutes,
        double averageSleepMinutes,
        List<ReplanSummary> replanSummary,
        List<DayEntry> days
) {
    public record DayEntry(LocalDate date, DayOfWeek dayOfWeek, RoutineMode mode, long sleepMinutes) {
    }
}

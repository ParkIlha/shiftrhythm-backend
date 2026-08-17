package com.shiftrhythm.backend.domain.report;

import java.time.YearMonth;
import java.util.List;

public record MonthlyReportView(
        YearMonth month,
        long totalSleepMinutes,
        double averageSleepMinutes,
        Double averageSleepMinutesPrevMonth,
        int replanDayCount,
        List<ReplanSummary> replanSummary
) {
}

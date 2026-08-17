package com.shiftrhythm.backend.domain.collectbook;

import java.time.YearMonth;
import java.util.List;

public record CollectbookView(
        YearMonth month,
        String summary,
        List<ZoneCard> zones,
        long totalTravelHours,
        long maxDailyTravelHours
) {
}

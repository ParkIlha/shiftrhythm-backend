package com.shiftrhythm.backend.domain.collectbook;

import java.time.LocalTime;

public record ZoneCard(
        int rank,
        int utcOffset,
        String city,
        int livedDays,
        LocalTime representativeSleepStart,
        LocalTime representativeSleepEnd,
        boolean isNew
) {
}

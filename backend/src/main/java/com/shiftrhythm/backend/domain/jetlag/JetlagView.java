package com.shiftrhythm.backend.domain.jetlag;

public record JetlagView(
        int utcOffset,
        String city,
        String message,
        long weeklyTravelHours,
        String weeklyMessage
) {
}

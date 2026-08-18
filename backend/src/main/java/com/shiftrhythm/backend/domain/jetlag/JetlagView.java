package com.shiftrhythm.backend.domain.jetlag;

public record JetlagView(
        int utcOffset,
        String country,
        String message,
        long dailyTravelHours,
        String dailyMessage
) {
}

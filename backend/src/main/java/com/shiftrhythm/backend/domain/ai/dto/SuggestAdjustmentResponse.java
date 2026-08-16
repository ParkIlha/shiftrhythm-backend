package com.shiftrhythm.backend.domain.ai.dto;

public record SuggestAdjustmentResponse(Sleep sleep, Meal meal) {

    public record Sleep(
            String mainSleepStart,
            String mainSleepEnd,
            String supplementarySleepStart,
            String supplementarySleepEnd,
            Integer napMinutes,
            String reason
    ) {
    }

    public record Meal(
            String mainMealTime,
            String subMealTime,
            boolean snackNeeded,
            String snackTime,
            String reason
    ) {
    }
}

package com.shiftrhythm.backend.domain.ai.dto;

import java.util.List;

public record SuggestAdjustmentRequest(
        String mode,
        SleepWindow sleepWindow,
        CurrentSleepBlock currentSleepBlock,
        MealConstraints mealConstraints,
        History history,
        String todayContext
) {
    /** AI가 절대 넘을 수 없는 하드 제약. */
    public record SleepWindow(String earliestSleepStart, String latestSleepEnd) {
    }

    /** 규칙 기반으로 미리 계산해 둔 초안 — AI는 이 안에서 자유롭게 재배치할 수 있다. */
    public record CurrentSleepBlock(
            String mainSleepStart,
            String mainSleepEnd,
            String supplementarySleepStart,
            String supplementarySleepEnd,
            Integer napMinutes,
            String ankerBlockStart,
            String ankerBlockEnd
    ) {
    }

    public record MealConstraints(
            String bigMealCutoff,
            String nightRestrictionStart,
            String nightRestrictionEnd
    ) {
    }

    public record History(
            List<String> recentSleepStarts,
            List<Integer> recentCondition,
            List<Integer> recentSleepSatisfaction,
            List<Integer> recentSleepLatencyMinutes,
            List<Integer> recentNightHunger,
            String rhythmPreference
    ) {
    }
}

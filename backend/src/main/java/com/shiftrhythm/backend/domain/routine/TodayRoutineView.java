package com.shiftrhythm.backend.domain.routine;

import com.shiftrhythm.backend.domain.jetlag.JetlagView;
import com.shiftrhythm.backend.domain.schedule.RoutineMode;
import com.shiftrhythm.backend.domain.schedule.TimelineSegment;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record TodayRoutineView(
        LocalDate date,
        RoutineMode mode,
        String modeReason,
        List<TimelineSegment> timeline,
        MealConstraintsView mealConstraints,
        String aiReason,
        boolean wasJustPersonalized,
        JetlagView jetlag,
        SleepDeficitView sleepDeficit
) {
    public record MealConstraintsView(LocalTime bigMealCutoff, LocalTime nightRestrictionStart, LocalTime nightRestrictionEnd, LocalTime caffeineCutoff) {
    }

    /** 최근 3일간 목표 수면시간 대비 부족했던 시간의 누적치(분). 0이면 부족분 없음. */
    public record SleepDeficitView(int deficitMinutes, String message) {
    }
}

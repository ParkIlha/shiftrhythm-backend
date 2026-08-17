package com.shiftrhythm.backend.domain.routine;

import com.shiftrhythm.backend.domain.schedule.RoutineMode;
import com.shiftrhythm.backend.domain.schedule.TimelineSegment;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record TodayRoutineView(
        LocalDate date,
        RoutineMode mode,
        List<TimelineSegment> timeline,
        MealConstraintsView mealConstraints,
        String aiReason,
        boolean wasJustPersonalized
) {
    public record MealConstraintsView(LocalTime bigMealCutoff, LocalTime nightRestrictionStart, LocalTime nightRestrictionEnd, LocalTime caffeineCutoff) {
    }
}

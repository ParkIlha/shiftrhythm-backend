package com.shiftrhythm.backend.domain.routine;

import com.shiftrhythm.backend.domain.schedule.MealBlock;
import com.shiftrhythm.backend.domain.schedule.RoutineMode;
import com.shiftrhythm.backend.domain.schedule.ShiftType;
import com.shiftrhythm.backend.domain.schedule.ShiftWindow;
import com.shiftrhythm.backend.domain.schedule.SleepBlock;
import com.shiftrhythm.backend.domain.schedule.SleepWindow;

import java.time.LocalDate;

public record RoutineComputation(
        LocalDate date,
        RoutineMode mode,
        ShiftWindow today,
        ShiftWindow next,
        SleepBlock sleepBlock,
        SleepWindow sleepWindow,
        MealBlock mealBlock
) {

    public RoutineSignature signature(boolean napAvailable) {
        return new RoutineSignature(mode, today.type(), next.type(), napAvailable);
    }

    public record RoutineSignature(RoutineMode mode, ShiftType todayType, ShiftType nextType, boolean napAvailable) {
    }
}

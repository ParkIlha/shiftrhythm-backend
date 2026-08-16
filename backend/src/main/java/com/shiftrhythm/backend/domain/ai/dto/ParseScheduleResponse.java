package com.shiftrhythm.backend.domain.ai.dto;

import java.util.List;

public record ParseScheduleResponse(List<ShiftTypeDef> shiftTypes, List<ShiftDay> shifts) {

    public record ShiftTypeDef(String shiftType, String startTime, String endTime) {
    }

    public record ShiftDay(String date, String shiftType) {
    }
}

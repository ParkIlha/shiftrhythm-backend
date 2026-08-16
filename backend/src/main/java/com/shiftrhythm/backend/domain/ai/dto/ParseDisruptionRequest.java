package com.shiftrhythm.backend.domain.ai.dto;

public record ParseDisruptionRequest(String rawText, ShiftContext shiftContext) {

    public record ShiftContext(String currentShift, String nextShift, String scheduledClockOut) {
    }
}

package com.shiftrhythm.backend.domain.ai.dto;

public record ParseDisruptionResponse(
        String eventType,
        int delayMinutes,
        String confirmedAt,
        String reasonCategory
) {
}

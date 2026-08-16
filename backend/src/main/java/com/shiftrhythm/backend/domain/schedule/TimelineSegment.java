package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalTime;

/**
 * type: "근무" | "주수면" | "보조수면" | "파워냅" | "주요식사" | "서브식사" | "자유시간"
 */
public record TimelineSegment(String type, LocalTime start, LocalTime end) {
}

package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalDate;

/**
 * ModeClassifier 입력용 — 날짜별 근무 유형만 담는 경량 값 타입.
 */
public record DaySchedule(LocalDate date, ShiftType shiftType) {
}

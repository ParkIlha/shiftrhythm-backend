package com.shiftrhythm.backend.domain.routine;

import com.shiftrhythm.backend.domain.schedule.ShiftType;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * startTime/endTime은 그날의 "유효 시각"이다 — 개별 날짜에 시각을 직접 지정(override)했으면 그 값,
 * 아니면 근무유형의 기본 시각(ShiftTypeDefault)을 대신 채워서 내려준다. hasCustomTime으로 어느 쪽인지 구분한다.
 */
public record ScheduleDayView(LocalDate date, ShiftType shiftType, LocalTime startTime, LocalTime endTime, boolean hasCustomTime) {
}

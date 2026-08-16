package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalTime;

/**
 * 특정 근무일의 유형과 실제 시각. OFF 날은 startTime/endTime이 null일 수 있다.
 */
public record ShiftWindow(ShiftType type, LocalTime startTime, LocalTime endTime) {
}

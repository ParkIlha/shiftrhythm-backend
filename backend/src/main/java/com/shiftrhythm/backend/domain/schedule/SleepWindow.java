package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalTime;

/**
 * AI가 수면을 배치할 수 있는 절대 하드 제약 범위. earliestSleepStart 이전, latestSleepEnd 이후로는
 * 절대 배치할 수 없다.
 */
public record SleepWindow(LocalTime earliestSleepStart, LocalTime latestSleepEnd) {
}

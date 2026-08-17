package com.shiftrhythm.backend.domain.jetlag;

import java.time.LocalDate;
import java.util.Map;

/**
 * [from, to] 구간에서 하루하루 offset이 얼마나 바뀌었는지(|오늘 offset - 어제 offset|)를 합산한다.
 * from의 델타는 from.minusDays(1)의 offset과 비교하므로, offsetByDate에 from 하루 전 데이터도
 * 포함돼 있어야 값이 잡힌다(없으면 그 날의 델타는 0으로 처리, 체인이 끊김).
 */
public final class DailyTravelCalculator {

    private DailyTravelCalculator() {
    }

    public record Result(long totalHours, long maxDailyHours) {
    }

    public static Result calculate(LocalDate from, LocalDate to, Map<LocalDate, Integer> offsetByDate) {
        long total = 0;
        long max = 0;
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            Integer todayOffset = offsetByDate.get(cursor);
            Integer prevOffset = offsetByDate.get(cursor.minusDays(1));
            if (todayOffset != null && prevOffset != null) {
                long delta = Math.abs(todayOffset - prevOffset);
                total += delta;
                max = Math.max(max, delta);
            }
            cursor = cursor.plusDays(1);
        }
        return new Result(total, max);
    }
}

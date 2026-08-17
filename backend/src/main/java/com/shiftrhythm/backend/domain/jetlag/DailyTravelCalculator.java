package com.shiftrhythm.backend.domain.jetlag;

import java.time.LocalDate;
import java.util.Map;

/**
 * [from, to] 구간에서 하루하루 offset이 얼마나 바뀌었는지를 합산한다.
 * from의 델타는 from.minusDays(1)의 offset과 비교하므로, offsetByDate에 from 하루 전 데이터도
 * 포함돼 있어야 값이 잡힌다(없으면 그 날의 델타는 0으로 처리, 체인이 끊김).
 *
 * 시간대는 -11~+12가 이어진 원형이라 두 존 사이 거리는 짧은 쪽으로 잰다 —
 * 예: +9(Seoul) → -6(Chicago)는 단순 뺄셈이면 15시간이지만 실제 이동은 9시간이고,
 * 하루 이동은 12시간을 넘을 수 없다.
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
                long gap = Math.abs(todayOffset - prevOffset);
                long delta = Math.min(gap, 24 - gap);
                total += delta;
                max = Math.max(max, delta);
            }
            cursor = cursor.plusDays(1);
        }
        return new Result(total, max);
    }
}

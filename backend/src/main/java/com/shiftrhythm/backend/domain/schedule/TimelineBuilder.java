package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 확정된 세그먼트(근무/주수면/보조수면/파워냅/주요식사/서브식사)를 시간순으로 정렬하고
 * 빈 구간을 "자유시간"으로 채운다. 순수함수, DB 접근 없음. 24시간 순환 구조로 취급한다.
 */
public final class TimelineBuilder {

    private TimelineBuilder() {
    }

    private static final long DAY_MINUTES = 24 * 60;

    public static List<TimelineSegment> build(List<TimelineSegment> knownSegments) {
        List<TimelineSegment> segments = knownSegments.stream()
                .filter(Objects::nonNull)
                .toList();
        if (segments.isEmpty()) {
            return List.of();
        }

        LocalTime anchor = segments.get(0).start();
        List<TimelineSegment> ordered = segments.stream()
                .sorted(Comparator.comparingLong(s -> SleepTimeMath.minutesBetween(anchor, s.start())))
                .toList();

        // anchor 기준 분 단위 offset으로 다룬다. 세그먼트끼리 겹치는 경우(예: 근무 중 식사)
        // occupiedUntil이 다음 세그먼트 시작보다 앞서지 않으므로 자유시간을 끼워넣지 않는다 —
        // 겹침을 "거의 하루 뒤 다음 일정"으로 오인해 24시간을 훌쩍 넘기던 버그(이슈 3)의 원인이었다.
        List<TimelineSegment> result = new ArrayList<>();
        long occupiedUntil = 0;
        for (TimelineSegment seg : ordered) {
            long segStart = SleepTimeMath.minutesBetween(anchor, seg.start());
            long segEnd = segStart + SleepTimeMath.minutesBetween(seg.start(), seg.end());
            if (segStart > occupiedUntil) {
                result.add(new TimelineSegment("자유시간", plusMinutes(anchor, occupiedUntil), seg.start()));
            }
            result.add(seg);
            occupiedUntil = Math.max(occupiedUntil, segEnd);
        }
        if (occupiedUntil < DAY_MINUTES) {
            result.add(new TimelineSegment("자유시간", plusMinutes(anchor, occupiedUntil), anchor));
        }
        return result;
    }

    private static LocalTime plusMinutes(LocalTime anchor, long minutes) {
        return anchor.plusMinutes(minutes % DAY_MINUTES);
    }
}

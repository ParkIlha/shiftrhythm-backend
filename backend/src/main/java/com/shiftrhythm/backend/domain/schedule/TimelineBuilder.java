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

        List<TimelineSegment> result = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            TimelineSegment current = ordered.get(i);
            result.add(current);
            LocalTime nextStart = (i + 1 < ordered.size()) ? ordered.get(i + 1).start() : ordered.get(0).start();
            long gap = SleepTimeMath.minutesBetween(current.end(), nextStart);
            if (gap > 0) {
                result.add(new TimelineSegment("자유시간", current.end(), nextStart));
            }
        }
        return result;
    }
}

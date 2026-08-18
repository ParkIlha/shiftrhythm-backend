package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * targetDate의 하루(00:00~24:00, 캘린더 기준)를 보여줄 타임라인을 만든다. 순수함수, DB 접근 없음.
 *
 * 세그먼트는 자정을 넘나들 수 있다(예: 어제 22:00 시작한 수면이 오늘 06:00에 끝남). 이런 세그먼트를
 * targetDate 경계에서 칼같이 자르지 않고, targetDate 하루와 겹치는 시간이 MIN_OVERLAP_MINUTES 이상이면
 * 통째로 포함한다 — 프론트에서 "이 블록이 사실 어제/내일 것"이라는 걸 알 수 있게 date를 그대로 들고 있게
 * 해서다. 겹침이 이 임계값 미만이면(예: 자정 10분 전에 끝나는 어제 일정) 노이즈로 보고 오늘 타임라인에서
 * 뺀다.
 */
public final class TimelineBuilder {

    private static final int MIN_OVERLAP_MINUTES = 30;

    private TimelineBuilder() {
    }

    public static List<TimelineSegment> buildForDay(List<TimelineSegment> candidateSegments, LocalDate targetDate) {
        LocalDateTime dayStart = targetDate.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);

        List<TimelineSegment> kept = candidateSegments.stream()
                .filter(Objects::nonNull)
                .filter(s -> overlapMinutes(realStart(s), realEnd(s), dayStart, dayEnd) >= MIN_OVERLAP_MINUTES)
                .sorted(Comparator.comparing(TimelineBuilder::realStart))
                .toList();

        List<TimelineSegment> result = new ArrayList<>();
        LocalDateTime cursor = dayStart;
        for (TimelineSegment seg : kept) {
            LocalDateTime segStart = realStart(seg);
            if (segStart.isAfter(cursor)) {
                result.add(freeTime(cursor, segStart));
            }
            result.add(seg);
            LocalDateTime segEnd = realEnd(seg);
            if (segEnd.isAfter(cursor)) {
                cursor = segEnd;
            }
        }
        if (dayEnd.isAfter(cursor)) {
            result.add(freeTime(cursor, dayEnd));
        }
        return result;
    }

    private static TimelineSegment freeTime(LocalDateTime from, LocalDateTime to) {
        return new TimelineSegment("자유시간", from.toLocalDate(), from.toLocalTime(), to.toLocalTime());
    }

    private static LocalDateTime realStart(TimelineSegment s) {
        return LocalDateTime.of(s.date(), s.start());
    }

    private static LocalDateTime realEnd(TimelineSegment s) {
        LocalDateTime end = LocalDateTime.of(s.date(), s.end());
        LocalDateTime start = LocalDateTime.of(s.date(), s.start());
        return end.isAfter(start) ? end : end.plusDays(1);
    }

    private static long overlapMinutes(LocalDateTime aStart, LocalDateTime aEnd, LocalDateTime bStart, LocalDateTime bEnd) {
        LocalDateTime start = aStart.isAfter(bStart) ? aStart : bStart;
        LocalDateTime end = aEnd.isBefore(bEnd) ? aEnd : bEnd;
        return java.time.Duration.between(start, end).toMinutes();
    }
}

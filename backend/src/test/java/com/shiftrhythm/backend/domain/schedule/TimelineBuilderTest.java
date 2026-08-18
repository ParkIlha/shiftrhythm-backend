package com.shiftrhythm.backend.domain.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineBuilderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 18);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Test
    void 겹치는_세그먼트가_있어도_하루를_넘기지_않는다() {
        // 이슈3 재현: 근무 06:00~14:00 중간에 주요식사 10:00~10:30, 주수면 11:45~18:45가 겹침
        List<TimelineSegment> known = List.of(
                new TimelineSegment("근무", TODAY, LocalTime.of(6, 0), LocalTime.of(14, 0)),
                new TimelineSegment("주요식사", TODAY, LocalTime.of(10, 0), LocalTime.of(10, 30)),
                new TimelineSegment("주수면", TODAY, LocalTime.of(11, 45), LocalTime.of(18, 45))
        );

        List<TimelineSegment> result = TimelineBuilder.buildForDay(known, TODAY);

        assertThat(result).allMatch(s -> s.date().equals(TODAY) || s.date().equals(TODAY.plusDays(1)));
        assertThat(result).noneMatch(s -> minutesOf(s) > 20 * 60);
    }

    @Test
    void 어제_수면이_자정을_30분_이상_넘기면_오늘_타임라인에_통째로_포함된다() {
        // 어제 22:00 시작해서 오늘 06:00에 끝나는 수면 — 자정 이후로 6시간 겹침(>=30분)
        List<TimelineSegment> candidates = List.of(
                new TimelineSegment("주수면", YESTERDAY, LocalTime.of(22, 0), LocalTime.of(6, 0))
        );

        List<TimelineSegment> result = TimelineBuilder.buildForDay(candidates, TODAY);

        assertThat(result).anyMatch(s -> s.type().equals("주수면") && s.date().equals(YESTERDAY)
                && s.start().equals(LocalTime.of(22, 0)) && s.end().equals(LocalTime.of(6, 0)));
    }

    @Test
    void 자정_10분_전에_끝나는_어제_세그먼트는_최소_겹침_기준_미만이라_제외된다() {
        List<TimelineSegment> candidates = List.of(
                new TimelineSegment("간식", YESTERDAY, LocalTime.of(23, 40), LocalTime.of(23, 50))
        );

        List<TimelineSegment> result = TimelineBuilder.buildForDay(candidates, TODAY);

        assertThat(result).noneMatch(s -> s.type().equals("간식"));
    }

    @Test
    void 겹침이_없으면_정확히_24시간을_채운다() {
        List<TimelineSegment> known = List.of(
                new TimelineSegment("주수면", TODAY, LocalTime.of(12, 44), LocalTime.of(20, 44)),
                new TimelineSegment("주요식사", TODAY, LocalTime.of(11, 30), LocalTime.of(12, 0))
        );

        List<TimelineSegment> result = TimelineBuilder.buildForDay(known, TODAY);

        long totalMinutes = result.stream().mapToLong(TimelineBuilderTest::minutesOf).sum();
        assertThat(totalMinutes).isEqualTo(24 * 60);
    }

    private static long minutesOf(TimelineSegment s) {
        var start = java.time.LocalDateTime.of(s.date(), s.start());
        var end = java.time.LocalDateTime.of(s.date(), s.end());
        if (!end.isAfter(start)) {
            end = end.plusDays(1);
        }
        return java.time.Duration.between(start, end).toMinutes();
    }
}

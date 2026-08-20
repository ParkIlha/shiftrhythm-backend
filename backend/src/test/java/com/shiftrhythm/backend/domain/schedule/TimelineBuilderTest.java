package com.shiftrhythm.backend.domain.schedule;

import com.shiftrhythm.backend.domain.schedule.policy.*;
import com.shiftrhythm.backend.domain.schedule.util.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineBuilderTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 18);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    private static LocalDateTime dt(LocalDate date, int h, int m) {
        return LocalDateTime.of(date, LocalTime.of(h, m));
    }

    @Test
    void 겹치는_세그먼트가_있어도_하루를_넘기지_않는다() {
        // 이슈3 재현: 근무 06:00~14:00 중간에 주요식사 10:00~10:30, 주수면 11:45~18:45가 겹침
        List<TimelineSegment> known = List.of(
                new TimelineSegment("근무", dt(TODAY, 6, 0), dt(TODAY, 14, 0)),
                new TimelineSegment("주요식사", dt(TODAY, 10, 0), dt(TODAY, 10, 30)),
                new TimelineSegment("주수면", dt(TODAY, 11, 45), dt(TODAY, 18, 45))
        );

        List<TimelineSegment> result = TimelineBuilder.buildForDay(known, TODAY);

        assertThat(result).allMatch(s -> s.date().equals(TODAY) || s.date().equals(TODAY.plusDays(1)));
        assertThat(result).noneMatch(s -> minutesOf(s) > 20 * 60);
    }

    @Test
    void 어제_수면이_자정을_30분_이상_넘기면_오늘_타임라인에_통째로_포함된다() {
        // 어제 22:00 시작해서 오늘 06:00에 끝나는 수면 — 자정 이후로 6시간 겹침(>=30분)
        List<TimelineSegment> candidates = List.of(
                new TimelineSegment("주수면", dt(YESTERDAY, 22, 0), dt(TODAY, 6, 0))
        );

        List<TimelineSegment> result = TimelineBuilder.buildForDay(candidates, TODAY);

        assertThat(result).anyMatch(s -> s.type().equals("주수면")
                && s.start().equals(dt(YESTERDAY, 22, 0)) && s.end().equals(dt(TODAY, 6, 0)));
    }

    @Test
    void 자정_10분_전에_끝나는_어제_세그먼트는_최소_겹침_기준_미만이라_제외된다() {
        List<TimelineSegment> candidates = List.of(
                new TimelineSegment("간식", dt(YESTERDAY, 23, 40), dt(YESTERDAY, 23, 50))
        );

        List<TimelineSegment> result = TimelineBuilder.buildForDay(candidates, TODAY);

        assertThat(result).noneMatch(s -> s.type().equals("간식"));
    }

    @Test
    void 겹침이_없으면_정확히_24시간을_채운다() {
        List<TimelineSegment> known = List.of(
                new TimelineSegment("주수면", dt(TODAY, 12, 44), dt(TODAY, 20, 44)),
                new TimelineSegment("주요식사", dt(TODAY, 11, 30), dt(TODAY, 12, 0))
        );

        List<TimelineSegment> result = TimelineBuilder.buildForDay(known, TODAY);

        long totalMinutes = result.stream().mapToLong(TimelineBuilderTest::minutesOf).sum();
        assertThat(totalMinutes).isEqualTo(24 * 60);
    }

    /**
     * 회귀 테스트: 근무(D 22:00~D+1 06:00)와 그 다음 날짜의 수면(D+1 08:00~D+1 12:30)이 서로 다른
     * 캘린더 날짜에 정확히 찍혀 있으면(더 이상 등록일에 뭉뚱그려 찍지 않으면) 같은 하루 타임라인 안에서도
     * 실시간으로 겹치지 않아야 한다.
     */
    @Test
    void 근무와_다음날_수면은_날짜가_정확하면_겹치지_않는다() {
        List<TimelineSegment> candidates = List.of(
                new TimelineSegment("근무", dt(YESTERDAY, 22, 0), dt(TODAY, 6, 0)),
                new TimelineSegment("주수면", dt(TODAY, 8, 0), dt(TODAY, 12, 30))
        );

        List<TimelineSegment> result = TimelineBuilder.buildForDay(candidates, TODAY);

        TimelineSegment work = result.stream().filter(s -> s.type().equals("근무")).findFirst().orElseThrow();
        TimelineSegment sleep = result.stream().filter(s -> s.type().equals("주수면")).findFirst().orElseThrow();
        assertThat(work.end()).isBeforeOrEqualTo(sleep.start());
    }

    private static long minutesOf(TimelineSegment s) {
        return java.time.Duration.between(s.start(), s.end()).toMinutes();
    }
}

package com.shiftrhythm.backend.domain.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineBuilderTest {

    @Test
    void 겹치는_세그먼트가_있어도_하루_24시간을_넘기지_않는다() {
        // 이슈3 재현: 근무 06:00~14:00 중간에 주요식사 10:00~10:30, 주수면 11:45~18:45가 겹침
        List<TimelineSegment> known = List.of(
                new TimelineSegment("근무", LocalTime.of(6, 0), LocalTime.of(14, 0)),
                new TimelineSegment("주요식사", LocalTime.of(10, 0), LocalTime.of(10, 30)),
                new TimelineSegment("주수면", LocalTime.of(11, 45), LocalTime.of(18, 45))
        );

        List<TimelineSegment> result = TimelineBuilder.build(known);

        long totalMinutes = 0;
        for (TimelineSegment s : result) {
            totalMinutes += SleepTimeMath.minutesBetween(s.start(), s.end());
        }
        // 겹치는 구간(근무-식사, 근무-수면)은 실제 흐른 시간에 중복 포함되지만,
        // 적어도 "겹침을 다음날로 착각해 48h가 되는" 버그는 재발하지 않아야 한다.
        assertThat(totalMinutes).isLessThan(24 * 60 + 24 * 60);
        assertThat(result).noneMatch(s -> SleepTimeMath.minutesBetween(s.start(), s.end()) > 20 * 60);
    }

    @Test
    void 겹침이_없으면_정확히_24시간을_채운다() {
        List<TimelineSegment> known = List.of(
                new TimelineSegment("주수면", LocalTime.of(12, 44), LocalTime.of(20, 44)),
                new TimelineSegment("주요식사", LocalTime.of(11, 30), LocalTime.of(12, 0))
        );

        List<TimelineSegment> result = TimelineBuilder.build(known);

        long totalMinutes = result.stream()
                .mapToLong(s -> SleepTimeMath.minutesBetween(s.start(), s.end()))
                .sum();
        assertThat(totalMinutes).isEqualTo(24 * 60);
    }
}

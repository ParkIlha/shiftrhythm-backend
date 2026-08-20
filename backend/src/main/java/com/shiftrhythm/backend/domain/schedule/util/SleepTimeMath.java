package com.shiftrhythm.backend.domain.schedule.util;
import com.shiftrhythm.backend.domain.schedule.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 시각 구간 계산 헬퍼.
 */
public final class SleepTimeMath {

    private SleepTimeMath() {
    }

    /**
     * 날짜 없이 시각(time-of-day)만의 순환 거리. from이 to보다 늦어도 다음날로 넘어간 것으로 계산한다.
     * 리듬 전환(offShift)처럼 "시각 자체"의 이동 거리를 비교할 때, 그리고 식사 제약처럼 날짜 개념이
     * 없는 하루 반복 구간을 다룰 때만 쓴다.
     */
    public static long minutesBetween(LocalTime from, LocalTime to) {
        long minutes = Duration.between(from, to).toMinutes();
        return minutes < 0 ? minutes + 24 * 60 : minutes;
    }

    /** 날짜를 포함한 실제 시각 간 분 차이. from이 to보다 늦으면 음수를 그대로 반환한다(순환 보정 없음). */
    public static long minutesBetween(LocalDateTime from, LocalDateTime to) {
        return Duration.between(from, to).toMinutes();
    }

    /**
     * AI가 "HH:mm" 문자열(날짜 없음)로 돌려준 시각을 sleepWindow[earliest, latest] 하드 제약 안에서
     * 유일하게 맞는 캘린더 날짜에 붙인다. ai-server는 날짜 계산을 하지 않는다는 원칙(시각 판단은
     * backend가 결정론적으로 한다)에 따라 "초안과 가까운 날짜" 같은 휴리스틱을 쓰지 않고, window
     * 범위(최대 24시간 폭) 안에 실제로 들어오는 날짜를 고른다 — earliest 날짜 기준과 그 다음날 중
     * 최대 하나만 범위 안에 들어올 수 있다. 둘 다 범위 밖이면(AI가 window를 완전히 벗어난 값을 준
     * 경우) 더 가까운 쪽을 골라 반환하고, 이후 AiSleepMealValidator의 clamp가 range 안으로 밀어넣는다.
     */
    public static LocalDateTime anchorWithinWindow(LocalTime time, LocalDateTime earliest, LocalDateTime latest) {
        LocalDateTime candidate1 = LocalDateTime.of(earliest.toLocalDate(), time);
        LocalDateTime candidate2 = candidate1.plusDays(1);
        boolean c1InRange = !candidate1.isBefore(earliest) && !candidate1.isAfter(latest);
        if (c1InRange) {
            return candidate1;
        }
        boolean c2InRange = !candidate2.isBefore(earliest) && !candidate2.isAfter(latest);
        if (c2InRange) {
            return candidate2;
        }
        return distanceToRange(candidate1, earliest, latest) <= distanceToRange(candidate2, earliest, latest)
                ? candidate1 : candidate2;
    }

    private static long distanceToRange(LocalDateTime t, LocalDateTime earliest, LocalDateTime latest) {
        if (t.isBefore(earliest)) {
            return Duration.between(t, earliest).toMinutes();
        }
        if (t.isAfter(latest)) {
            return Duration.between(latest, t).toMinutes();
        }
        return 0;
    }
}

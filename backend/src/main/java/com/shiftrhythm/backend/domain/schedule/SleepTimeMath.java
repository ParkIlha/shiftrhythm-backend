package com.shiftrhythm.backend.domain.schedule;

import java.time.Duration;
import java.time.LocalTime;

/**
 * 자정을 넘나드는 LocalTime 구간 계산 헬퍼. 모든 SleepBlock 시각은 날짜 없이 LocalTime만으로
 * 표현되므로(자정을 넘겨도 시작일 기준), from -> to 구간이 다음날로 넘어갈 수 있음을 항상 감안한다.
 */
public final class SleepTimeMath {

    private SleepTimeMath() {
    }

    public static long minutesBetween(LocalTime from, LocalTime to) {
        long minutes = Duration.between(from, to).toMinutes();
        return minutes < 0 ? minutes + 24 * 60 : minutes;
    }
}

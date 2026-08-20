package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalDate;

/**
 * SleepBlockCalculator 입력. 호출부(서비스 계층)가 DB에서 조회한 값을 조립해서 넘긴다 —
 * 계산 로직 자체는 DB에 의존하지 않는다.
 *
 * DAY/EVENING/NIGHT 안정 모드: today = 오늘 근무, next = 다음 근무(같은 유형)
 * SHIFT_TRANSITION: today = 오늘 근무, next = 내일 근무(다른 유형)
 * OFF_* 모드: today = OFF(type만 유효, 시각 null), next = 휴무 후 재개되는 근무
 *
 * date: 이 계산이 속한 캘린더 날짜(등록 기준일). prevSleepBlock/anchor가 전혀 없는 최후 폴백에서만
 * 절대 시각을 만드는 데 쓰인다.
 */
public record SleepBlockContext(
        RoutineMode mode,
        LocalDate date,
        ShiftWindow today,
        ShiftWindow next,
        int commuteMinutes,
        int prepMinutes,
        int targetSleepMinutes,
        boolean napAvailable,
        Integer napAvailableMinutes,
        RhythmPreference rhythmPreference,
        SleepBlock prevSleepBlock,
        int offDayIndex,
        int offDayTotal,
        int recentSleepDeficitMinutes
) {
}

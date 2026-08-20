package com.shiftrhythm.backend.domain.routine;

import java.time.LocalDate;

/**
 * 근무표 등록(POST /api/onboarding/schedule) 시 제출된 shifts 범위에 오늘 날짜가 없으면 던진다.
 * PATCH /api/onboarding/schedule/{date}는 기존 날짜 수정 전용이라 새 날짜(오늘)를 추가할 수 없으므로,
 * 이 경우 사용자가 오늘을 포함한 근무표를 통째로 다시 등록하는 것만이 유일한 해결 방법이다 — 그래서
 * 저장 자체를 막아서 이후 /routines/today 등에서 뒤늦게 터지는 걸 방지한다.
 */
public class ScheduleMissingTodayException extends RuntimeException {
    private final LocalDate today;

    public ScheduleMissingTodayException(LocalDate today) {
        super("SCHEDULE_MISSING_TODAY: " + today);
        this.today = today;
    }

    public LocalDate today() {
        return today;
    }
}

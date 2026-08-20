package com.shiftrhythm.backend.domain.routine;

import java.time.LocalTime;

/**
 * mainMeal1/mainMeal2는 둘 다 항상 있어야 하는 필수 끼니(대등, 위계 없음). snackTime은 선택.
 * bigMealCutoff/caffeineCutoff는 그날 실제 확정된 수면 시작 기준으로 계산된 값을 그대로 보관한다
 * (프론트에 안내용으로 노출).
 */
public record MealTimes(
        LocalTime mainMeal1,
        LocalTime mainMeal2,
        LocalTime snackTime,
        LocalTime bigMealCutoff,
        LocalTime caffeineCutoff
) {
}

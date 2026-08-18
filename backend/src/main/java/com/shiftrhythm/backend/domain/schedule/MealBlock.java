package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalTime;

/**
 * 식사 시각의 "절대 침범 불가 울타리"만 담는다. 실제 식사 시각은 AI가 판단.
 *
 * <p>nightRestriction 구간이 막는 건 <b>주요식사(큰 식사)뿐</b>이다 — 그 시간에 깨어 근무 중이면
 * 가벼운 보조식사·간식은 오히려 권장된다(AiSleepMealValidator.clampMainMeal 도 주요식사만 민다).
 */
public record MealBlock(
        LocalTime bigMealCutoff,
        LocalTime nightRestrictionStart,
        LocalTime nightRestrictionEnd,
        LocalTime caffeineCutoff
) {
}

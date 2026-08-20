package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalDateTime;

/**
 * 식사 시각의 "절대 침범 불가 울타리". 실제 식사 시각은 AI가 판단(백엔드는 clamp만 한다).
 *
 * bigMealCutoff/caffeineCutoff는 그날 실제 주수면 시작(mainSleepStart) 기준으로 날짜까지 포함해
 * 계산된다. mainSleep/supplementarySleep 구간을 그대로 들고 있는 이유는, 끼니 검증
 * (AiSleepMealValidator)이 "수면 중엔 못 먹는다"는 하드 제약을 직접 비교해야 하기 때문이다.
 *
 * nightRestriction(생체야간 00~06시) 개념은 더 이상 백엔드 하드 제약이 아니다 — AI 프롬프트 권고로
 * 격하됐다(별도 조율 예정). 여기서는 필드 자체를 없앴다.
 */
public record MealBlock(
        LocalDateTime bigMealCutoff,
        LocalDateTime caffeineCutoff,
        LocalDateTime mainSleepStart,
        LocalDateTime mainSleepEnd,
        LocalDateTime supplementarySleepStart,
        LocalDateTime supplementarySleepEnd
) {
}

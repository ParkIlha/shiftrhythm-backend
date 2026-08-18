package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalTime;

public final class MealBlockCalculator {

    private MealBlockCalculator() {
    }

    /** 카페인 반감기가 보통 5~6시간이라, 취침 5시간 전부터는 새로 섭취하지 않도록 한다. (NIOSH) */
    private static final int CAFFEINE_CUTOFF_BEFORE_SLEEP_MINUTES = 5 * 60;

    /**
     * 큰 식사는 취침 2시간 전까지. 통상 권고는 2~3시간 전이고, 교대근무자는 애초에 수면 창이
     * 좁아 3시간을 요구하면 끼니를 건너뛰게 되므로 하한인 2시간을 쓴다.
     * (이전 값은 90분이었는데 어느 지침에도 근거가 없어 2시간으로 올림.)
     */
    private static final int BIG_MEAL_CUTOFF_BEFORE_SLEEP_MINUTES = 2 * 60;

    /**
     * 생체 야간(소화 능력이 가장 낮은 구간)은 근무 시간이 아니라 사람의 생체시계를 따른다 —
     * 야간근무자도 일주기 리듬은 완전히 이동하지 않기 때문에, 근무표와 무관하게 벽시계 기준으로 고정한다.
     * "이 구간엔 아무것도 먹지 마라"가 아니라 "큰 식사를 두지 마라"는 뜻이다. 깨어 근무 중이라면
     * 가벼운 식사는 오히려 권장되며, 그 판단은 AI(subMeal/snack)가 한다. 여기서 막는 건 주요식사뿐이다.
     */
    private static final LocalTime NIGHT_RESTRICTION_START = LocalTime.MIDNIGHT;
    private static final LocalTime NIGHT_RESTRICTION_END = LocalTime.of(6, 0);

    public static MealBlock calculate(LocalTime sleepStart) {
        return new MealBlock(
                sleepStart.minusMinutes(BIG_MEAL_CUTOFF_BEFORE_SLEEP_MINUTES),
                NIGHT_RESTRICTION_START,
                NIGHT_RESTRICTION_END,
                sleepStart.minusMinutes(CAFFEINE_CUTOFF_BEFORE_SLEEP_MINUTES)
        );
    }
}

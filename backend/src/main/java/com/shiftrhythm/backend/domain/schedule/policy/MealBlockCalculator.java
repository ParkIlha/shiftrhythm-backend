package com.shiftrhythm.backend.domain.schedule.policy;
import com.shiftrhythm.backend.domain.schedule.*;

public final class MealBlockCalculator {

    private MealBlockCalculator() {
    }

    /** 카페인 반감기가 보통 5~6시간이라, 취침 5시간 전부터는 새로 섭취하지 않도록 한다. (NIOSH) */
    private static final int CAFFEINE_CUTOFF_BEFORE_SLEEP_MINUTES = 5 * 60;

    /**
     * 큰 식사는 취침 2시간 전까지. 통상 권고는 2~3시간 전이고, 교대근무자는 애초에 수면 창이
     * 좁아 3시간을 요구하면 끼니를 건너뛰게 되므로 하한인 2시간을 쓴다.
     */
    private static final int BIG_MEAL_CUTOFF_BEFORE_SLEEP_MINUTES = 2 * 60;

    /** 그날의 실제(날짜 포함) SleepBlock을 기준으로 식사 울타리를 계산한다. */
    public static MealBlock calculate(SleepBlock sleepBlock) {
        return new MealBlock(
                sleepBlock.mainSleepStart().minusMinutes(BIG_MEAL_CUTOFF_BEFORE_SLEEP_MINUTES),
                sleepBlock.mainSleepStart().minusMinutes(CAFFEINE_CUTOFF_BEFORE_SLEEP_MINUTES),
                sleepBlock.mainSleepStart(),
                sleepBlock.mainSleepEnd(),
                sleepBlock.supplementarySleepStart(),
                sleepBlock.supplementarySleepEnd()
        );
    }
}

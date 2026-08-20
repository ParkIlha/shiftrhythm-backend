package com.shiftrhythm.backend.domain.schedule.policy;
import com.shiftrhythm.backend.domain.schedule.*;
import com.shiftrhythm.backend.domain.schedule.util.*;

import java.time.LocalDateTime;

/**
 * NIGHT 근무를 마친 이후 다음 근무 전까지의 수면을 주수면/보조수면으로 배분한다.
 * (NIGHT → NIGHT, NIGHT → EVENING에서만 사용)
 */
public final class NightSplitAllocator {

    private static final int NAP_CAP_MINUTES = 30;

    private NightSplitAllocator() {
    }

    public static SleepBlock allocate(
            LocalDateTime windowStart,
            LocalDateTime windowEnd,
            int targetSleepMinutes,
            boolean napAvailable,
            Integer napAvailableMinutes
    ) {
        long availableMinutes = SleepTimeMath.minutesBetween(windowStart, windowEnd);

        int mainTarget = Math.round(targetSleepMinutes * 4.5f / 7f);
        int supplementaryTarget = Math.round(targetSleepMinutes * 2.5f / 7f);

        int mainSleep;
        int supplementarySleep;
        if (availableMinutes >= mainTarget + supplementaryTarget) {
            mainSleep = mainTarget;
            supplementarySleep = supplementaryTarget;
        } else if (availableMinutes >= mainTarget) {
            mainSleep = mainTarget;
            supplementarySleep = (int) (availableMinutes - mainTarget);
        } else {
            mainSleep = (int) Math.max(0, availableMinutes);
            supplementarySleep = 0;
        }

        int shortage = targetSleepMinutes - (mainSleep + supplementarySleep);
        Integer napMinutes = null;
        if (shortage > 0 && napAvailable) {
            int cap = napAvailableMinutes == null ? NAP_CAP_MINUTES : Math.min(napAvailableMinutes, NAP_CAP_MINUTES);
            napMinutes = Math.min(shortage, cap);
        }

        LocalDateTime mainSleepStart = windowStart;
        LocalDateTime mainSleepEnd = windowStart.plusMinutes(mainSleep);

        LocalDateTime supplementaryStart = null;
        LocalDateTime supplementaryEnd = null;
        if (supplementarySleep > 0) {
            supplementaryEnd = windowEnd;
            supplementaryStart = windowEnd.minusMinutes(supplementarySleep);
        }

        return new SleepBlock(
                mainSleepStart, mainSleepEnd,
                supplementaryStart, supplementaryEnd,
                napMinutes,
                60,
                null, null
        );
    }
}

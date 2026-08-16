package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalTime;

public record SleepBlock(
        LocalTime mainSleepStart,
        LocalTime mainSleepEnd,
        LocalTime supplementarySleepStart,
        LocalTime supplementarySleepEnd,
        Integer napMinutes,
        int adjustToleranceMinutes,
        LocalTime ankerBlockStart,
        LocalTime ankerBlockEnd
) {
    public SleepBlock withAnchor(Anchor anchor) {
        if (anchor == null) {
            return this;
        }
        return new SleepBlock(
                mainSleepStart, mainSleepEnd,
                supplementarySleepStart, supplementarySleepEnd,
                napMinutes, adjustToleranceMinutes,
                anchor.start(), anchor.end()
        );
    }

    public SleepBlock withTolerance(int tolerance) {
        return new SleepBlock(
                mainSleepStart, mainSleepEnd,
                supplementarySleepStart, supplementarySleepEnd,
                napMinutes, tolerance,
                ankerBlockStart, ankerBlockEnd
        );
    }
}

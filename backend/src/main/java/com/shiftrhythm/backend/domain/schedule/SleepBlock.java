package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalDateTime;

public record SleepBlock(
        LocalDateTime mainSleepStart,
        LocalDateTime mainSleepEnd,
        LocalDateTime supplementarySleepStart,
        LocalDateTime supplementarySleepEnd,
        Integer napMinutes,
        int adjustToleranceMinutes,
        LocalDateTime ankerBlockStart,
        LocalDateTime ankerBlockEnd
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

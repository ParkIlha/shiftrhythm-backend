package com.shiftrhythm.backend.domain.schedule;

import com.shiftrhythm.backend.domain.schedule.policy.*;
import com.shiftrhythm.backend.domain.schedule.util.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class NightSplitAllocatorTest {

    private static final int TARGET = 420; // 7h
    private static final LocalDate D = LocalDate.of(2024, 1, 1);

    private static LocalDateTime t(int h, int m) {
        return LocalDateTime.of(D, LocalTime.of(h, m));
    }

    @Test
    void ns01_availableExceedsTarget_fullMainAndSupplementary() {
        SleepBlock block = NightSplitAllocator.allocate(
                t(8, 0), t(16, 30), TARGET, false, null);
        assertThat(block.mainSleepEnd().toLocalTime().equals(LocalTime.of(12, 30))).isTrue(); // 08:00 + 270min
        assertThat(block.supplementarySleepStart().toLocalTime()).isEqualTo(LocalTime.of(14, 0)); // 16:30 - 150min
        assertThat(block.supplementarySleepEnd().toLocalTime()).isEqualTo(LocalTime.of(16, 30));
        assertThat(block.napMinutes()).isNull();
    }

    @Test
    void ns03_mainMetButSupplementaryShrinks() {
        SleepBlock block = NightSplitAllocator.allocate(
                t(8, 0), t(13, 50), TARGET, false, null); // available=350min
        assertThat(block.mainSleepEnd().toLocalTime()).isEqualTo(LocalTime.of(12, 30)); // main=270
        assertThat(block.supplementarySleepStart().toLocalTime()).isEqualTo(LocalTime.of(12, 30)); // supp=80
        assertThat(block.supplementarySleepEnd().toLocalTime()).isEqualTo(LocalTime.of(13, 50));
        assertThat(block.napMinutes()).isNull();
    }

    @Test
    void ns04_availableBelowMainTarget_wholeWindowIsMain() {
        SleepBlock block = NightSplitAllocator.allocate(
                t(8, 0), t(11, 20), TARGET, false, null); // available=200min < mainTarget(270)
        assertThat(block.mainSleepStart()).isEqualTo(t(8, 0));
        assertThat(block.mainSleepEnd()).isEqualTo(t(11, 20));
        assertThat(block.supplementarySleepStart()).isNull();
        assertThat(block.napMinutes()).isNull();
    }

    @Test
    void ns05_deficitWithNapAvailable_capsAt30() {
        SleepBlock block = NightSplitAllocator.allocate(
                t(8, 0), t(11, 20), TARGET, true, 60); // shortage=220
        assertThat(block.napMinutes()).isEqualTo(30);
    }

    @Test
    void ns06_deficitWithoutNap_returnsNullNap() {
        SleepBlock block = NightSplitAllocator.allocate(
                t(8, 0), t(11, 20), TARGET, false, null);
        assertThat(block.napMinutes()).isNull();
    }

    @Test
    void windowBoundsAreNeverExceeded() {
        SleepBlock block = NightSplitAllocator.allocate(
                t(23, 0), t(6, 0).plusDays(1), TARGET, true, 30); // wraps past midnight
        assertThat(block.mainSleepStart()).isEqualTo(t(23, 0));
        assertThat(SleepTimeMath.minutesBetween(t(23, 0), block.mainSleepEnd()))
                .isLessThanOrEqualTo(SleepTimeMath.minutesBetween(LocalTime.of(23, 0), LocalTime.of(6, 0)));
    }
}

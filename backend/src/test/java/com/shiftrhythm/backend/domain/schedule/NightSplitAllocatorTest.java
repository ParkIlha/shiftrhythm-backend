package com.shiftrhythm.backend.domain.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class NightSplitAllocatorTest {

    private static final int TARGET = 420; // 7h

    @Test
    void ns01_availableExceedsTarget_fullMainAndSupplementary() {
        SleepBlock block = NightSplitAllocator.allocate(
                LocalTime.of(8, 0), LocalTime.of(16, 30), TARGET, false, null);
        assertThat(block.mainSleepEnd().equals(LocalTime.of(12, 30))).isTrue(); // 08:00 + 270min
        assertThat(block.supplementarySleepStart()).isEqualTo(LocalTime.of(14, 0)); // 16:30 - 150min
        assertThat(block.supplementarySleepEnd()).isEqualTo(LocalTime.of(16, 30));
        assertThat(block.napMinutes()).isNull();
    }

    @Test
    void ns03_mainMetButSupplementaryShrinks() {
        SleepBlock block = NightSplitAllocator.allocate(
                LocalTime.of(8, 0), LocalTime.of(13, 50), TARGET, false, null); // available=350min
        assertThat(block.mainSleepEnd()).isEqualTo(LocalTime.of(12, 30)); // main=270
        assertThat(block.supplementarySleepStart()).isEqualTo(LocalTime.of(12, 30)); // supp=80
        assertThat(block.supplementarySleepEnd()).isEqualTo(LocalTime.of(13, 50));
        assertThat(block.napMinutes()).isNull();
    }

    @Test
    void ns04_availableBelowMainTarget_wholeWindowIsMain() {
        SleepBlock block = NightSplitAllocator.allocate(
                LocalTime.of(8, 0), LocalTime.of(11, 20), TARGET, false, null); // available=200min < mainTarget(270)
        assertThat(block.mainSleepStart()).isEqualTo(LocalTime.of(8, 0));
        assertThat(block.mainSleepEnd()).isEqualTo(LocalTime.of(11, 20));
        assertThat(block.supplementarySleepStart()).isNull();
        assertThat(block.napMinutes()).isNull();
    }

    @Test
    void ns05_deficitWithNapAvailable_capsAt30() {
        SleepBlock block = NightSplitAllocator.allocate(
                LocalTime.of(8, 0), LocalTime.of(11, 20), TARGET, true, 60); // shortage=220
        assertThat(block.napMinutes()).isEqualTo(30);
    }

    @Test
    void ns06_deficitWithoutNap_returnsNullNap() {
        SleepBlock block = NightSplitAllocator.allocate(
                LocalTime.of(8, 0), LocalTime.of(11, 20), TARGET, false, null);
        assertThat(block.napMinutes()).isNull();
    }

    @Test
    void windowBoundsAreNeverExceeded() {
        SleepBlock block = NightSplitAllocator.allocate(
                LocalTime.of(23, 0), LocalTime.of(6, 0), TARGET, true, 30); // wraps past midnight
        assertThat(block.mainSleepStart()).isEqualTo(LocalTime.of(23, 0));
        assertThat(SleepTimeMath.minutesBetween(LocalTime.of(23, 0), block.mainSleepEnd()))
                .isLessThanOrEqualTo(SleepTimeMath.minutesBetween(LocalTime.of(23, 0), LocalTime.of(6, 0)));
    }
}

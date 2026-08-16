package com.shiftrhythm.backend.domain.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class AnchorSizerTest {

    @Test
    void balancedPreference_anchorIs300Minutes() {
        SleepBlock prev = new SleepBlock(LocalTime.of(22, 30), LocalTime.of(5, 30), null, null, null, 60, null, null);
        Anchor anchor = AnchorSizer.size(prev, RhythmPreference.BALANCED);
        assertThat(anchor.start()).isEqualTo(LocalTime.of(22, 30));
        assertThat(anchor.end()).isEqualTo(LocalTime.of(3, 30));
    }

    @Test
    void rhythmLean_anchorIs360Minutes() {
        SleepBlock prev = new SleepBlock(LocalTime.of(23, 0), LocalTime.of(7, 0), null, null, null, 60, null, null);
        Anchor anchor = AnchorSizer.size(prev, RhythmPreference.RHYTHM_LEAN);
        assertThat(anchor.end()).isEqualTo(LocalTime.of(5, 0));
    }

    @Test
    void dayLean_anchorIs240Minutes() {
        SleepBlock prev = new SleepBlock(LocalTime.of(23, 0), LocalTime.of(6, 0), null, null, null, 60, null, null);
        Anchor anchor = AnchorSizer.size(prev, RhythmPreference.DAY_LEAN);
        assertThat(anchor.end()).isEqualTo(LocalTime.of(3, 0));
    }

    @Test
    void noPrevSleepBlock_returnsNull() {
        assertThat(AnchorSizer.size(null, RhythmPreference.BALANCED)).isNull();
    }
}

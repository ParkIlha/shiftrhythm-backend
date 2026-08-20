package com.shiftrhythm.backend.domain.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class AnchorSizerTest {

    private static final LocalDate D = LocalDate.of(2024, 1, 1);

    private static LocalDateTime t(int h, int m) {
        return LocalDateTime.of(D, LocalTime.of(h, m));
    }

    @Test
    void balancedPreference_anchorIs300Minutes() {
        SleepBlock prev = new SleepBlock(t(22, 30), t(5, 30), null, null, null, 60, null, null);
        Anchor anchor = AnchorSizer.size(prev, RhythmPreference.BALANCED);
        assertThat(anchor.start().toLocalTime()).isEqualTo(LocalTime.of(22, 30));
        assertThat(anchor.end().toLocalTime()).isEqualTo(LocalTime.of(3, 30));
    }

    @Test
    void rhythmLean_anchorIs360Minutes() {
        SleepBlock prev = new SleepBlock(t(23, 0), t(7, 0), null, null, null, 60, null, null);
        Anchor anchor = AnchorSizer.size(prev, RhythmPreference.RHYTHM_LEAN);
        assertThat(anchor.end().toLocalTime()).isEqualTo(LocalTime.of(5, 0));
    }

    @Test
    void dayLean_anchorIs240Minutes() {
        SleepBlock prev = new SleepBlock(t(23, 0), t(6, 0), null, null, null, 60, null, null);
        Anchor anchor = AnchorSizer.size(prev, RhythmPreference.DAY_LEAN);
        assertThat(anchor.end().toLocalTime()).isEqualTo(LocalTime.of(3, 0));
    }

    @Test
    void noPrevSleepBlock_returnsNull() {
        assertThat(AnchorSizer.size(null, RhythmPreference.BALANCED)).isNull();
    }
}

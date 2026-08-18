package com.shiftrhythm.backend.domain.jetlag;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class JetlagMapperTest {

    @Test
    void baselineWakeTime_mapsToKorea() {
        JetlagZone zone = JetlagMapper.zoneOf(LocalTime.of(7, 0));
        assertThat(zone.utcOffset()).isEqualTo(9);
        assertThat(zone.country()).isEqualTo("Korea");
    }

    @Test
    void afternoonWake_mapsToFrance() {
        JetlagZone zone = JetlagMapper.zoneOf(LocalTime.of(15, 0));
        assertThat(zone.utcOffset()).isEqualTo(1);
        assertThat(zone.country()).isEqualTo("France");
    }

    @Test
    void nightShiftWake_mapsToCostaRica() {
        JetlagZone zone = JetlagMapper.zoneOf(LocalTime.of(22, 0));
        assertThat(zone.utcOffset()).isEqualTo(-6);
        assertThat(zone.country()).isEqualTo("Costa Rica");
    }

    @Test
    void noonWake_mapsToUnitedArabEmirates() {
        // minutesBetween(12:00,16:00)=4h -> offset 4
        JetlagZone zone = JetlagMapper.zoneOf(LocalTime.of(12, 0));
        assertThat(zone.utcOffset()).isEqualTo(4);
        assertThat(zone.country()).isEqualTo("United Arab Emirates");
    }

    @Test
    void exactlyOppositeWake_mapsToBoundaryOffset() {
        // 16:00 기상 -> minutesBetween(16:00,16:00)=0 -> offset 0 (UK)
        JetlagZone zone = JetlagMapper.zoneOf(LocalTime.of(16, 0));
        assertThat(zone.utcOffset()).isZero();
        assertThat(zone.country()).isEqualTo("UK");
    }

    @Test
    void offsetOf_wrapsToNegativeRangeBeyond12Hours() {
        // 16:30 기상 -> minutesBetween(16:30,16:00)=(16:00-16:30)mod1440=1410min=23.5h -> round 24 -> mod24=0
        int offset = JetlagMapper.offsetOf(LocalTime.of(16, 30));
        assertThat(offset).isZero();
    }
}

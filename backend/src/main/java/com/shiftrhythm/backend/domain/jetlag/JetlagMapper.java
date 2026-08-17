package com.shiftrhythm.backend.domain.jetlag;

import com.shiftrhythm.backend.domain.schedule.SleepTimeMath;

import java.time.LocalTime;
import java.util.Map;

/**
 * 기상시각을 "몇 시간대(zone)에서 사는 것과 같은가"로 은유하는 순수함수.
 *
 * 기준: 07:00 기상 = UTC+9(서울). 기상시각이 07:00에서 벗어난 만큼, "그 시각에 07:00을 맞이하는
 * 시간대가 어디인지"를 역산한다.
 *
 * 유도: offsetMinutes = (기준 기상시각 07:00의 분 + KST 09:00의 분) - 기상시각의 분, mod 1440
 *      = minutesBetween(기상시각, 16:00)  [07:00 + 9h = 16:00]
 * 이 값을 시간 단위로 반올림하고, 12시간을 넘으면 24를 빼서 -11~+12 범위(24개 존)로 맞춘다.
 *
 * 검증: 07:00 기상 → +9(Seoul, 기준점) / 15:00 기상 → +1(Paris) / 22:00 기상 → -6(Chicago)
 */
public final class JetlagMapper {

    private static final LocalTime BASELINE_REFERENCE = LocalTime.of(16, 0); // 07:00(기상) + 9h(KST)

    private static final Map<Integer, String> CITY_BY_OFFSET = Map.ofEntries(
            Map.entry(-11, "Pago Pago"), Map.entry(-10, "Honolulu"), Map.entry(-9, "Anchorage"),
            Map.entry(-8, "Los Angeles"), Map.entry(-7, "Denver"), Map.entry(-6, "Chicago"),
            Map.entry(-5, "New York"), Map.entry(-4, "Halifax"), Map.entry(-3, "Buenos Aires"),
            Map.entry(-2, "Vila dos Remédios"), Map.entry(-1, "Praia"), Map.entry(0, "London"),
            Map.entry(1, "Paris"), Map.entry(2, "Johannesburg"), Map.entry(3, "Riyadh"),
            Map.entry(4, "Dubai"), Map.entry(5, "Karachi"), Map.entry(6, "Dhaka"),
            Map.entry(7, "Bangkok"), Map.entry(8, "Singapore"), Map.entry(9, "Seoul"),
            Map.entry(10, "Brisbane"), Map.entry(11, "Nouméa"), Map.entry(12, "Suva")
    );

    private JetlagMapper() {
    }

    public static int offsetOf(LocalTime wakeTime) {
        long minutes = SleepTimeMath.minutesBetween(wakeTime, BASELINE_REFERENCE);
        long hours = Math.round(minutes / 60.0) % 24;
        return hours > 12 ? (int) (hours - 24) : (int) hours;
    }

    public static JetlagZone zoneOf(LocalTime wakeTime) {
        int offset = offsetOf(wakeTime);
        return new JetlagZone(offset, CITY_BY_OFFSET.get(offset));
    }

    public static String cityOf(int utcOffset) {
        return CITY_BY_OFFSET.get(utcOffset);
    }
}

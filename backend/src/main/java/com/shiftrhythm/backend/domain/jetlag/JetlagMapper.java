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
 * 검증: 07:00 기상 → +9(Korea, 기준점) / 15:00 기상 → +1(France) / 22:00 기상 → -6(Costa Rica)
 */
public final class JetlagMapper {

    private static final LocalTime BASELINE_REFERENCE = LocalTime.of(16, 0); // 07:00(기상) + 9h(KST)

    /**
     * +9는 이 매핑의 기준점(baseline)이라 "Korea"로 고정한다
     * (나머지는 기획에서 받은 나라 매핑을 그대로 따름).
     */
    private static final Map<Integer, String> COUNTRY_BY_OFFSET = Map.ofEntries(
            Map.entry(-11, "Samoa"), Map.entry(-10, "USA"), Map.entry(-9, "USA"),
            Map.entry(-8, "Canada"), Map.entry(-7, "Mexico"), Map.entry(-6, "Costa Rica"),
            Map.entry(-5, "Brazil"), Map.entry(-4, "Chile"), Map.entry(-3, "Argentina"),
            Map.entry(-2, "Brazil"), Map.entry(-1, "Portugal"), Map.entry(0, "UK"),
            Map.entry(1, "France"), Map.entry(2, "Egypt"), Map.entry(3, "Russia"),
            Map.entry(4, "United Arab Emirates"), Map.entry(5, "Pakistan"), Map.entry(6, "Bangladesh"),
            Map.entry(7, "Thailand"), Map.entry(8, "China"), Map.entry(9, "Korea"),
            Map.entry(10, "Australia"), Map.entry(11, "Solomon Islands"), Map.entry(12, "New Zealand")
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
        return new JetlagZone(offset, COUNTRY_BY_OFFSET.get(offset));
    }

    public static String countryOf(int utcOffset) {
        return COUNTRY_BY_OFFSET.get(utcOffset);
    }
}

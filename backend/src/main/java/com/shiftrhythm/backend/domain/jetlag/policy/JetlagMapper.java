package com.shiftrhythm.backend.domain.jetlag.policy;
import com.shiftrhythm.backend.domain.jetlag.*;

import com.shiftrhythm.backend.domain.schedule.util.SleepTimeMath;

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
 * 검증: 07:00 기상 → +9(한국, 기준점) / 15:00 기상 → +1(프랑스) / 22:00 기상 → -6(코스타리카)
 */
public final class JetlagMapper {

    private static final LocalTime BASELINE_REFERENCE = LocalTime.of(16, 0); // 07:00(기상) + 9h(KST)

    /**
     * +9는 이 매핑의 기준점(baseline)이라 "한국"으로 고정한다
     * (나머지는 기획에서 받은 나라 매핑을 그대로 따름).
     */
    private static final Map<Integer, String> COUNTRY_BY_OFFSET = Map.ofEntries(
            Map.entry(-11, "사모아"), Map.entry(-10, "미국"), Map.entry(-9, "미국"),
            Map.entry(-8, "캐나다"), Map.entry(-7, "멕시코"), Map.entry(-6, "코스타리카"),
            Map.entry(-5, "브라질"), Map.entry(-4, "칠레"), Map.entry(-3, "아르헨티나"),
            Map.entry(-2, "브라질"), Map.entry(-1, "포르투갈"), Map.entry(0, "영국"),
            Map.entry(1, "프랑스"), Map.entry(2, "이집트"), Map.entry(3, "러시아"),
            Map.entry(4, "아랍에미리트"), Map.entry(5, "파키스탄"), Map.entry(6, "방글라데시"),
            Map.entry(7, "태국"), Map.entry(8, "중국"), Map.entry(9, "한국"),
            Map.entry(10, "호주"), Map.entry(11, "솔로몬 제도"), Map.entry(12, "뉴질랜드")
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

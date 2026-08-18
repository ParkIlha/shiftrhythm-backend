package com.shiftrhythm.backend.domain.jetlag;

import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import com.shiftrhythm.backend.web.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 메인페이지 시차 표시 위젯. 오늘의 존(zone)과 "이번 주(월~일) 총 이동시간"을 계산한다.
 * 이동시간은 하루하루 offset이 얼마나 바뀌었는지(|오늘 offset - 어제 offset|)의 합이며,
 * 월요일의 이동시간은 지난주 일요일 offset과의 차이로 계산한다(주 경계를 넘어 이어지는 흐름 반영).
 */
@Service
public class JetlagService {

    private final RoutineResultRepository routineResultRepository;

    public JetlagService(RoutineResultRepository routineResultRepository) {
        this.routineResultRepository = routineResultRepository;
    }

    public JetlagView todayView(LocalDate date, LocalTime todayWakeTime, String userName) {
        JetlagZone todayZone = JetlagMapper.zoneOf(todayWakeTime);
        long weeklyTravelHours = weeklyTravelHours(date);

        return new JetlagView(
                todayZone.utcOffset(),
                todayZone.city(),
                "현재 %s님은 %s 시간대에 살고 있어요.".formatted(userName, todayZone.city()),
                weeklyTravelHours,
                "이번주는 %d시간을 건넙니다.".formatted(weeklyTravelHours)
        );
    }

    private long weeklyTravelHours(LocalDate date) {
        Long userId = CurrentUser.id();
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate lookbackStart = monday.minusDays(1); // 월요일 이동시간 계산용 지난주 일요일

        List<RoutineResult> rows = routineResultRepository
                .findByUserProfileIdAndDateBetweenAndIsCurrentTrueOrderByDateAsc(userId, lookbackStart, date);

        Map<LocalDate, Integer> offsetByDate = new LinkedHashMap<>();
        for (RoutineResult r : rows) {
            offsetByDate.put(r.getDate(), JetlagMapper.offsetOf(r.getSleepEnd()));
        }

        return DailyTravelCalculator.calculate(monday, date, offsetByDate).totalHours();
    }
}

package com.shiftrhythm.backend.domain.jetlag;

import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import com.shiftrhythm.backend.web.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

/**
 * 메인페이지 시차 표시 위젯. 오늘의 존(zone)과 "어제→오늘 이동시간"을 계산한다.
 * 이동시간은 어제 기상시각의 존과 오늘 존 사이의 원형 거리(시간)다.
 */
@Service
public class JetlagService {

    private final RoutineResultRepository routineResultRepository;

    public JetlagService(RoutineResultRepository routineResultRepository) {
        this.routineResultRepository = routineResultRepository;
    }

    public JetlagView todayView(LocalDate date, LocalTime todayWakeTime, String userName) {
        JetlagZone todayZone = JetlagMapper.zoneOf(todayWakeTime);
        long dailyTravelHours = dailyTravelHours(date, todayZone.utcOffset());

        return new JetlagView(
                todayZone.utcOffset(),
                todayZone.city(),
                "현재 %s님은 %s 시간대에 살고 있어요.".formatted(userName, todayZone.city()),
                dailyTravelHours,
                "오늘은 %d시간을 건너요.".formatted(dailyTravelHours)
        );
    }

    /** 어제 기상시각의 존과 오늘 존 사이의 원형 거리(시간). 어제 기록이 없으면 0. */
    private long dailyTravelHours(LocalDate date, int todayOffset) {
        Long userId = CurrentUser.id();
        LocalDate yesterday = date.minusDays(1);

        return routineResultRepository.findByUserProfileIdAndDateAndIsCurrentTrue(userId, yesterday)
                .map(r -> {
                    Map<LocalDate, Integer> offsetByDate = Map.of(
                            yesterday, JetlagMapper.offsetOf(r.getSleepEnd()),
                            date, todayOffset);
                    return DailyTravelCalculator.calculate(date, date, offsetByDate).totalHours();
                })
                .orElse(0L);
    }
}

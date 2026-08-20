package com.shiftrhythm.backend.domain.jetlag;

import com.shiftrhythm.backend.domain.jetlag.service.JetlagService;
import com.shiftrhythm.backend.domain.routine.MealTimes;
import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.RoutineMode;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class JetlagServiceTest {

    @Autowired
    private JetlagService jetlagService;
    @Autowired
    private RoutineResultRepository routineResultRepository;

    private static MealTimes mealTimes() {
        return new MealTimes(LocalTime.of(7, 0), null, null,
                LocalTime.of(21, 0), LocalTime.of(0, 0), LocalTime.of(6, 0), LocalTime.of(18, 0));
    }

    private void save(LocalDate date, LocalTime wakeTime) {
        LocalDateTime wake = LocalDateTime.of(date, wakeTime);
        routineResultRepository.save(new RoutineResult(UserProfile.SINGLETON_ID, date, 1, true, null, RoutineMode.DAY,
                wake.minusHours(7), wake, null, null, null, mealTimes()));
    }

    @Test
    void todayView_reflectsTodaysZone() {
        LocalDate today = LocalDate.of(2026, 8, 3);
        save(today, LocalTime.of(7, 0)); // offset 9 (한국)

        JetlagView view = jetlagService.todayView(today, LocalTime.of(7, 0), "테스터");

        assertThat(view.utcOffset()).isEqualTo(9);
        assertThat(view.country()).isEqualTo("한국");
        assertThat(view.message()).contains("한국");
    }

    @Test
    void dailyTravelHours_isDeltaFromYesterday() {
        LocalDate yesterday = LocalDate.of(2026, 8, 3);
        LocalDate today = yesterday.plusDays(1);

        save(yesterday, LocalTime.of(7, 0));  // offset 9
        save(today, LocalTime.of(15, 0));     // offset 1 -> |1-9|=8

        JetlagView view = jetlagService.todayView(today, LocalTime.of(15, 0), "테스터");

        assertThat(view.dailyTravelHours()).isEqualTo(8);
        assertThat(view.dailyMessage()).contains("8");
        assertThat(view.dailyMessage()).contains("오늘은");
    }

    @Test
    void dailyTravelHours_usesCircularDistance() {
        LocalDate yesterday = LocalDate.of(2026, 8, 3);
        LocalDate today = yesterday.plusDays(1);

        save(yesterday, LocalTime.of(7, 0));   // offset 9 (한국)
        save(today, LocalTime.of(22, 0));      // offset -6 (코스타리카) -> 원형 거리 min(15, 9) = 9

        JetlagView view = jetlagService.todayView(today, LocalTime.of(22, 0), "테스터");

        assertThat(view.dailyTravelHours()).isEqualTo(9);
    }

    @Test
    void dailyTravelHours_noYesterdayRecord_isZero() {
        LocalDate today = LocalDate.of(2026, 8, 3);
        save(today, LocalTime.of(22, 0));

        JetlagView view = jetlagService.todayView(today, LocalTime.of(22, 0), "테스터");

        assertThat(view.dailyTravelHours()).isZero();
    }
}

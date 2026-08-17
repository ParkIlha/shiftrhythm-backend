package com.shiftrhythm.backend.domain.jetlag;

import com.shiftrhythm.backend.domain.routine.MealTimes;
import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.RoutineMode;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
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
        routineResultRepository.save(new RoutineResult(UserProfile.SINGLETON_ID, date, 1, true, null, RoutineMode.DAY,
                wakeTime.minusHours(7), wakeTime, null, null, null, mealTimes()));
    }

    @Test
    void todayView_reflectsTodaysZone() {
        LocalDate monday = LocalDate.of(2026, 8, 3);
        save(monday, LocalTime.of(7, 0)); // offset 9 (Seoul)

        JetlagView view = jetlagService.todayView(monday, LocalTime.of(7, 0), "테스터");

        assertThat(view.utcOffset()).isEqualTo(9);
        assertThat(view.city()).isEqualTo("Seoul");
        assertThat(view.message()).contains("Seoul");
    }

    @Test
    void weeklyTravelHours_sumsDailyOffsetDeltasWithinWeek() {
        LocalDate monday = LocalDate.of(2026, 8, 3);
        assertThat(monday.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);

        save(monday, LocalTime.of(7, 0));               // offset 9
        save(monday.plusDays(1), LocalTime.of(15, 0));   // offset 1 -> |1-9|=8
        save(monday.plusDays(2), LocalTime.of(22, 0));   // offset -6 -> |-6-1|=7

        JetlagView view = jetlagService.todayView(monday.plusDays(2), LocalTime.of(22, 0), "테스터");

        assertThat(view.weeklyTravelHours()).isEqualTo(15); // 8 + 7
        assertThat(view.weeklyMessage()).contains("15");
    }

    @Test
    void weeklyTravelHours_mondayCountsDeltaFromPreviousSunday() {
        LocalDate sunday = LocalDate.of(2026, 8, 2);
        LocalDate monday = sunday.plusDays(1);
        assertThat(sunday.getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);

        save(sunday, LocalTime.of(7, 0));   // offset 9 (지난주)
        save(monday, LocalTime.of(22, 0));  // offset -6 -> |(-6)-9|=15

        JetlagView view = jetlagService.todayView(monday, LocalTime.of(22, 0), "테스터");

        assertThat(view.weeklyTravelHours()).isEqualTo(15);
    }

    @Test
    void weeklyTravelHours_missingDayBreaksTheChain() {
        LocalDate monday = LocalDate.of(2026, 8, 3);
        save(monday, LocalTime.of(7, 0));                // offset 9
        // 화요일(월+1) 데이터 없음 -> 수요일과의 델타는 계산 안 됨
        save(monday.plusDays(2), LocalTime.of(22, 0));   // offset -6, but 전날(화) 데이터 없어 델타 미포함

        JetlagView view = jetlagService.todayView(monday.plusDays(2), LocalTime.of(22, 0), "테스터");

        assertThat(view.weeklyTravelHours()).isZero();
    }
}

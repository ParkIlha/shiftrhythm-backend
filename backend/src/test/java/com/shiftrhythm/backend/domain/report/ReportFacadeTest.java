package com.shiftrhythm.backend.domain.report;

import com.shiftrhythm.backend.domain.report.service.ReportFacade;
import com.shiftrhythm.backend.domain.checkin.entity.DailyCheckIn;
import com.shiftrhythm.backend.domain.checkin.repository.DailyCheckInRepository;
import com.shiftrhythm.backend.domain.routine.MealTimes;
import com.shiftrhythm.backend.domain.routine.ReplanReason;
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
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ReportFacadeTest {

    private static final LocalDate D0 = LocalDate.of(2026, 8, 3); // 월요일

    @Autowired
    private ReportFacade reportFacade;
    @Autowired
    private RoutineResultRepository routineResultRepository;
    @Autowired
    private DailyCheckInRepository dailyCheckInRepository;

    private static MealTimes mealTimes() {
        return new MealTimes(LocalTime.of(7, 0), null, null,
                LocalTime.of(21, 0), LocalTime.of(18, 0));
    }

    /** date의 start~end 구간을 LocalDateTime으로 만든다. end가 start보다 이르면 자정을 넘긴 것으로 본다. */
    private static LocalDateTime start(LocalDate date, LocalTime start) {
        return LocalDateTime.of(date, start);
    }

    private static LocalDateTime end(LocalDate date, LocalTime start, LocalTime end) {
        return end.isBefore(start) ? LocalDateTime.of(date.plusDays(1), end) : LocalDateTime.of(date, end);
    }

    private static RoutineResult routine(LocalDate date, int version, boolean current, ReplanReason reason,
                                          LocalTime sleepStart, LocalTime sleepEnd) {
        return new RoutineResult(UserProfile.SINGLETON_ID, date, version, current, reason, RoutineMode.DAY,
                start(date, sleepStart), end(date, sleepStart, sleepEnd), null, null, null, mealTimes());
    }

    @Test
    void weekly_aggregatesSleepAndReplanSummary() {
        // D0: 7시간 수면, 재계획 없음
        routineResultRepository.save(routine(D0, 1, true, null, LocalTime.of(23, 0), LocalTime.of(6, 0)));

        // D0+1: 최초(v1) 6시간 -> 재계획(v2, LATE_CLOCKOUT)으로 5시간, v2가 is_current(=지켜짐)
        LocalDate d1 = D0.plusDays(1);
        routineResultRepository.save(routine(d1, 1, false, null, LocalTime.of(23, 0), LocalTime.of(5, 0)));
        routineResultRepository.save(routine(d1, 2, true, ReplanReason.LATE_CLOCKOUT, LocalTime.of(0, 0), LocalTime.of(5, 0)));

        WeeklyReportView view = reportFacade.weekly(D0, D0.plusDays(6));

        assertThat(view.days()).hasSize(2);
        assertThat(view.totalSleepMinutes()).isEqualTo(7 * 60 + 5 * 60);
        assertThat(view.averageSleepMinutes()).isEqualTo((7 * 60 + 5 * 60) / 2.0);

        assertThat(view.replanSummary()).hasSize(1);
        ReplanSummary summary = view.replanSummary().get(0);
        assertThat(summary.reason()).isEqualTo(ReplanReason.LATE_CLOCKOUT);
        assertThat(summary.totalCount()).isEqualTo(1);
        assertThat(summary.keptCount()).isEqualTo(1); // v2가 is_current로 남아있으므로 "지켜짐"
    }

    @Test
    void monthly_comparesAgainstPreviousMonth() {
        YearMonth thisMonth = YearMonth.from(D0);
        YearMonth prevMonth = thisMonth.minusMonths(1);

        routineResultRepository.save(routine(thisMonth.atDay(1), 1, true, null, LocalTime.of(23, 0), LocalTime.of(7, 0))); // 8시간
        routineResultRepository.save(routine(prevMonth.atDay(1), 1, true, null, LocalTime.of(23, 0), LocalTime.of(5, 0))); // 6시간

        MonthlyReportView view = reportFacade.monthly(thisMonth);

        assertThat(view.averageSleepMinutes()).isEqualTo(8 * 60.0);
        assertThat(view.averageSleepMinutesPrevMonth()).isEqualTo(6 * 60.0);
        assertThat(view.replanDayCount()).isZero();
    }

    @Test
    void monthly_withNoPreviousMonthData_averageIsNull() {
        YearMonth farFuture = YearMonth.from(D0).plusYears(5);
        routineResultRepository.save(routine(farFuture.atDay(1), 1, true, null, LocalTime.of(23, 0), LocalTime.of(7, 0)));

        MonthlyReportView view = reportFacade.monthly(farFuture);

        assertThat(view.averageSleepMinutesPrevMonth()).isNull();
    }

    @Test
    void daily_comparesPlanVsActualAndIncludesReplanLogAndCheckIn() {
        routineResultRepository.save(routine(D0, 1, false, null, LocalTime.of(23, 0), LocalTime.of(6, 0)));
        routineResultRepository.save(routine(D0, 2, true, ReplanReason.SHIFT_CHANGE, LocalTime.of(22, 0), LocalTime.of(5, 30)));

        DailyCheckIn checkIn = new DailyCheckIn(UserProfile.SINGLETON_ID, D0);
        checkIn.setConditionScore(4);
        checkIn.setSleepSatisfaction(3);
        dailyCheckInRepository.save(checkIn);

        DailyReportView view = reportFacade.daily(D0);

        assertThat(view.sleep().plannedSleepStart()).isEqualTo(LocalTime.of(23, 0));
        assertThat(view.sleep().actualSleepStart()).isEqualTo(LocalTime.of(22, 0));

        assertThat(view.replanLog()).hasSize(1);
        DailyReportView.ReplanLogEntry log = view.replanLog().get(0);
        assertThat(log.version()).isEqualTo(2);
        assertThat(log.reason()).isEqualTo(ReplanReason.SHIFT_CHANGE);
        assertThat(log.sleepStartBefore()).isEqualTo(LocalTime.of(23, 0));
        assertThat(log.sleepStartAfter()).isEqualTo(LocalTime.of(22, 0));

        assertThat(view.checkIn()).isNotNull();
        assertThat(view.checkIn().conditionScore()).isEqualTo(4);
        assertThat(view.checkIn().sleepSatisfaction()).isEqualTo(3);
    }

    @Test
    void daily_withoutCheckIn_checkInViewIsNull() {
        routineResultRepository.save(routine(D0, 1, true, null, LocalTime.of(23, 0), LocalTime.of(6, 0)));

        DailyReportView view = reportFacade.daily(D0);

        assertThat(view.checkIn()).isNull();
        assertThat(view.replanLog()).isEmpty();
    }
}

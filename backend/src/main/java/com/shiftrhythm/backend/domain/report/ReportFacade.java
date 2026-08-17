package com.shiftrhythm.backend.domain.report;

import com.shiftrhythm.backend.domain.checkin.entity.DailyCheckIn;
import com.shiftrhythm.backend.domain.checkin.repository.DailyCheckInRepository;
import com.shiftrhythm.backend.domain.routine.ReplanReason;
import com.shiftrhythm.backend.domain.routine.RoutineNotFoundException;
import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.SleepTimeMath;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 기록 분석 리포트(주간/월간/일별) 조회. RoutineResult(버전별)와 DailyCheckIn만으로 전부 계산하며
 * 별도로 저장하는 값은 없다.
 */
@Service
@Transactional(readOnly = true)
public class ReportFacade {

    private final RoutineResultRepository routineResultRepository;
    private final DailyCheckInRepository dailyCheckInRepository;

    public ReportFacade(RoutineResultRepository routineResultRepository, DailyCheckInRepository dailyCheckInRepository) {
        this.routineResultRepository = routineResultRepository;
        this.dailyCheckInRepository = dailyCheckInRepository;
    }

    public WeeklyReportView weekly(LocalDate from, LocalDate to) {
        Long userId = UserProfile.SINGLETON_ID;
        List<RoutineResult> currentRows = routineResultRepository
                .findByUserProfileIdAndDateBetweenAndIsCurrentTrueOrderByDateAsc(userId, from, to);

        long totalMinutes = 0;
        List<WeeklyReportView.DayEntry> days = new ArrayList<>();
        for (RoutineResult r : currentRows) {
            long minutes = sleepMinutes(r);
            totalMinutes += minutes;
            days.add(new WeeklyReportView.DayEntry(r.getDate(), r.getDate().getDayOfWeek(), r.getMode(), minutes));
        }
        double average = currentRows.isEmpty() ? 0 : (double) totalMinutes / currentRows.size();

        List<ReplanSummary> replanSummary = replanSummaryOf(userId, from, to);

        return new WeeklyReportView(from, to, totalMinutes, average, replanSummary, days);
    }

    public MonthlyReportView monthly(YearMonth month) {
        Long userId = UserProfile.SINGLETON_ID;
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        List<RoutineResult> currentRows = routineResultRepository
                .findByUserProfileIdAndDateBetweenAndIsCurrentTrueOrderByDateAsc(userId, from, to);
        long totalMinutes = currentRows.stream().mapToLong(this::sleepMinutes).sum();
        double average = currentRows.isEmpty() ? 0 : (double) totalMinutes / currentRows.size();

        YearMonth prevMonth = month.minusMonths(1);
        List<RoutineResult> prevRows = routineResultRepository.findByUserProfileIdAndDateBetweenAndIsCurrentTrueOrderByDateAsc(
                userId, prevMonth.atDay(1), prevMonth.atEndOfMonth());
        Double prevAverage = prevRows.isEmpty() ? null
                : (double) prevRows.stream().mapToLong(this::sleepMinutes).sum() / prevRows.size();

        List<RoutineResult> replans = routineResultRepository
                .findByUserProfileIdAndDateBetweenAndVersionGreaterThanOrderByDateAscVersionAsc(userId, from, to, 1);
        int replanDayCount = (int) replans.stream().map(RoutineResult::getDate).distinct().count();

        List<ReplanSummary> replanSummary = summarize(replans, currentRows);

        return new MonthlyReportView(month, totalMinutes, average, prevAverage, replanDayCount, replanSummary);
    }

    public DailyReportView daily(LocalDate date) {
        Long userId = UserProfile.SINGLETON_ID;
        RoutineResult current = routineResultRepository.findByUserProfileIdAndDateAndIsCurrentTrue(userId, date)
                .orElseThrow(() -> new RoutineNotFoundException(date));
        RoutineResult plan = routineResultRepository.findByUserProfileIdAndDateAndVersion(userId, date, 1)
                .orElse(current);

        DailyReportView.PlanVsActual sleep = new DailyReportView.PlanVsActual(
                plan.getMode(), plan.getSleepStart(), plan.getSleepEnd(), sleepMinutes(plan),
                current.getMode(), current.getSleepStart(), current.getSleepEnd(), sleepMinutes(current)
        );

        List<RoutineResult> versions = routineResultRepository.findByUserProfileIdAndDateOrderByVersionAsc(userId, date);
        List<DailyReportView.ReplanLogEntry> replanLog = new ArrayList<>();
        for (int i = 1; i < versions.size(); i++) {
            RoutineResult before = versions.get(i - 1);
            RoutineResult after = versions.get(i);
            replanLog.add(new DailyReportView.ReplanLogEntry(
                    after.getVersion(), after.getReplanReason(), after.getAiReason(),
                    before.getSleepStart(), after.getSleepStart(),
                    before.getSleepEnd(), after.getSleepEnd()
            ));
        }

        DailyReportView.CheckInView checkInView = dailyCheckInRepository.findByUserProfileIdAndDate(userId, date)
                .map(this::toCheckInView)
                .orElse(null);

        return new DailyReportView(date, sleep, replanLog, checkInView);
    }

    private DailyReportView.CheckInView toCheckInView(DailyCheckIn c) {
        return new DailyReportView.CheckInView(
                c.getConditionScore(), c.getSleepSatisfaction(), c.getSleepLatencyMinutes(), c.getNightHungerScore());
    }

    private List<ReplanSummary> replanSummaryOf(Long userId, LocalDate from, LocalDate to) {
        List<RoutineResult> replans = routineResultRepository
                .findByUserProfileIdAndDateBetweenAndVersionGreaterThanOrderByDateAscVersionAsc(userId, from, to, 1);
        List<RoutineResult> currentRows = routineResultRepository
                .findByUserProfileIdAndDateBetweenAndIsCurrentTrueOrderByDateAsc(userId, from, to);
        return summarize(replans, currentRows);
    }

    /**
     * replans(version>1 전체)를 사유별로 집계한다. "지켜졌는지"는 그 행이 해당 날짜의 is_current
     * 행과 동일한지(=이후 추가 수정 없이 최종본으로 남아있는지)로 판단한다.
     */
    private List<ReplanSummary> summarize(List<RoutineResult> replans, List<RoutineResult> currentRows) {
        Map<LocalDate, RoutineResult> currentByDate = new LinkedHashMap<>();
        for (RoutineResult r : currentRows) {
            currentByDate.put(r.getDate(), r);
        }

        Map<ReplanReason, int[]> counts = new LinkedHashMap<>();
        for (RoutineResult r : replans) {
            var reason = r.getReplanReason();
            if (reason == null) {
                continue;
            }
            int[] bucket = counts.computeIfAbsent(reason, k -> new int[2]);
            bucket[0]++;
            RoutineResult current = currentByDate.get(r.getDate());
            if (current != null && current.getId().equals(r.getId())) {
                bucket[1]++;
            }
        }

        List<ReplanSummary> result = new ArrayList<>();
        for (var entry : counts.entrySet()) {
            result.add(new ReplanSummary(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
        }
        return result;
    }

    private long sleepMinutes(RoutineResult r) {
        long minutes = SleepTimeMath.minutesBetween(r.getSleepStart(), r.getSleepEnd());
        if (r.getSupplementarySleepStart() != null && r.getSupplementarySleepEnd() != null) {
            minutes += SleepTimeMath.minutesBetween(r.getSupplementarySleepStart(), r.getSupplementarySleepEnd());
        }
        return minutes;
    }
}

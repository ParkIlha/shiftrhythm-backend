package com.shiftrhythm.backend.domain.collectbook;

import com.shiftrhythm.backend.domain.jetlag.DailyTravelCalculator;
import com.shiftrhythm.backend.domain.jetlag.JetlagMapper;
import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import com.shiftrhythm.backend.web.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 콜렉트북. RoutineResult(is_current)만 스캔해서 매번 계산하며 별도 저장값은 없다.
 * 시간대(zone) 식별은 JetlagMapper.offsetOf(sleepEnd)로 시차 위젯과 동일한 기준을 쓴다.
 */
@Service
@Transactional(readOnly = true)
public class CollectbookFacade {

    private static final int ROUND_MINUTES = 15;

    private final RoutineResultRepository routineResultRepository;

    public CollectbookFacade(RoutineResultRepository routineResultRepository) {
        this.routineResultRepository = routineResultRepository;
    }

    public CollectbookView get(YearMonth month) {
        Long userId = CurrentUser.id();
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();

        List<RoutineResult> rows = routineResultRepository
                .findByUserProfileIdAndDateBetweenAndIsCurrentTrueOrderByDateAsc(userId, from, to);

        Map<Integer, List<RoutineResult>> byOffset = new LinkedHashMap<>();
        for (RoutineResult r : rows) {
            int offset = JetlagMapper.offsetOf(r.getSleepEnd());
            byOffset.computeIfAbsent(offset, k -> new ArrayList<>()).add(r);
        }

        Set<Integer> historicalOffsets = routineResultRepository
                .findByUserProfileIdAndDateBeforeAndIsCurrentTrueOrderByDateAsc(userId, from).stream()
                .map(r -> JetlagMapper.offsetOf(r.getSleepEnd()))
                .collect(Collectors.toSet());

        List<ZoneAggregate> aggregates = byOffset.entrySet().stream()
                .map(e -> aggregate(e.getKey(), e.getValue(), historicalOffsets))
                .sorted(Comparator.comparingInt(ZoneAggregate::livedDays).reversed()
                        .thenComparing(ZoneAggregate::lastLivedDate, Comparator.reverseOrder()))
                .toList();

        List<ZoneCard> zones = new ArrayList<>();
        for (int i = 0; i < aggregates.size(); i++) {
            ZoneAggregate a = aggregates.get(i);
            zones.add(new ZoneCard(i + 1, a.offset(), JetlagMapper.countryOf(a.offset()), a.livedDays(),
                    a.representativeSleepStart(), a.representativeSleepEnd(), a.isNew()));
        }

        DailyTravelCalculator.Result travel = monthlyTravel(userId, from, to);

        String summary = buildSummary(month, zones);

        return new CollectbookView(month, summary, zones, travel.totalHours(), travel.maxDailyHours());
    }

    private record ZoneAggregate(int offset, int livedDays, LocalDate lastLivedDate,
                                  LocalTime representativeSleepStart, LocalTime representativeSleepEnd, boolean isNew) {
    }

    private ZoneAggregate aggregate(int offset, List<RoutineResult> rowsInZone, Set<Integer> historicalOffsets) {
        LocalDate lastLivedDate = rowsInZone.stream().map(RoutineResult::getDate).max(LocalDate::compareTo).orElseThrow();
        LocalTime repStart = mostFrequent(rowsInZone.stream().map(r -> roundToNearest(r.getSleepStart(), ROUND_MINUTES)).toList());
        LocalTime repEnd = mostFrequent(rowsInZone.stream().map(r -> roundToNearest(r.getSleepEnd(), ROUND_MINUTES)).toList());
        boolean isNew = !historicalOffsets.contains(offset);
        return new ZoneAggregate(offset, rowsInZone.size(), lastLivedDate, repStart, repEnd, isNew);
    }

    private DailyTravelCalculator.Result monthlyTravel(Long userId, LocalDate from, LocalDate to) {
        LocalDate lookbackStart = from.minusDays(1); // 전달 마지막날과 이어서 계산
        List<RoutineResult> rows = routineResultRepository
                .findByUserProfileIdAndDateBetweenAndIsCurrentTrueOrderByDateAsc(userId, lookbackStart, to);
        Map<LocalDate, Integer> offsetByDate = new LinkedHashMap<>();
        for (RoutineResult r : rows) {
            offsetByDate.put(r.getDate(), JetlagMapper.offsetOf(r.getSleepEnd()));
        }
        return DailyTravelCalculator.calculate(from, to, offsetByDate);
    }

    private String buildSummary(YearMonth month, List<ZoneCard> zones) {
        if (zones.isEmpty()) {
            return "%d월에는 기록이 없어요.".formatted(month.getMonthValue());
        }
        boolean isCurrentMonth = month.equals(YearMonth.now());
        if (isCurrentMonth) {
            ZoneCard top = zones.get(0);
            return "%d월 현재 %d개의 시간대에서 살고 있어요. 가장 오래 머문 시간대는 %s Time · %d일이에요."
                    .formatted(month.getMonthValue(), zones.size(), top.country(), top.livedDays());
        }
        return "%d월에는 %d개의 시간대에서 살았어요.".formatted(month.getMonthValue(), zones.size());
    }

    /** 동률이면 먼저 나온(더 이른 순서) 값을 채택한다. */
    private static LocalTime mostFrequent(List<LocalTime> times) {
        Map<LocalTime, Integer> counts = new LinkedHashMap<>();
        for (LocalTime t : times) {
            counts.merge(t, 1, Integer::sum);
        }
        LocalTime best = null;
        int bestCount = -1;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private static LocalTime roundToNearest(LocalTime time, int minutes) {
        int totalMinutes = time.getHour() * 60 + time.getMinute();
        int rounded = Math.round((float) totalMinutes / minutes) * minutes;
        rounded = ((rounded % 1440) + 1440) % 1440;
        return LocalTime.of(rounded / 60, rounded % 60);
    }
}

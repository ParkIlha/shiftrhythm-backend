package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 순수함수. 근무표(과거~미래 포함 범위)를 보고 targetDate가 7개 모드 중 무엇인지 판정한다.
 *
 * 오늘이 근무일인가?
 * ├─ Y → 내일도 근무일인가?
 * │      ├─ Y → 내일 근무유형 == 오늘 근무유형? → 오늘 유형 유지 : SHIFT_TRANSITION
 * │      └─ N → 오늘 유형 유지
 * └─ N → targetDate가 속한 연속휴무 구간의 총 길이가 2일 이상인가? (offStreakOf 기준, 매일 재판정해도
 *        구간 전체에 걸쳐 동일하게 판정되도록 구간 길이로 계산 — 마지막 날에만 다른 모드로 튀는 것을 방지)
 *        ├─ Y → OFF_RECOVERY
 *        └─ N → 휴무 전 근무유형 == 휴무 후 근무유형? → OFF_RHYTHM_MAINTAIN : OFF_RHYTHM_SHIFT
 *
 * 휴무 전 근무유형을 알 수 없는 경우(등록된 근무표 맨 앞의 OFF 구간)나 휴무 후 근무유형을 알 수 없는
 * 경우(등록된 근무표 맨 뒤)에는 OFF_RHYTHM_MAINTAIN으로 폴백한다(기준블록 없음 폴백 사용).
 *
 * 계약: classify()/offStreakOf()는 targetDate 기준 과거 방향으로도 탐색하므로(findPreviousWorkShiftType,
 * offStreakOf의 역방향 스캔), 호출부는 targetDate 이전 구간이 잘리지 않은 schedule을 넘겨야 한다.
 * 현재 유일한 호출부인 RoutineComputationService.compute()는 매번 유저의 전체 근무표를 조회해서 넘기므로
 * 문제없지만, 향후 조회 범위를 date range로 제한하도록 최적화할 경우 이 전제가 깨져 휴무 구간 판정이
 * 잘못될 수 있다.
 */
public final class ModeClassifier {

    private ModeClassifier() {
    }

    public static RoutineMode classify(List<DaySchedule> schedule, LocalDate targetDate) {
        Map<LocalDate, ShiftType> byDate = schedule.stream()
                .collect(Collectors.toMap(DaySchedule::date, DaySchedule::shiftType, (a, b) -> a));

        ShiftType today = byDate.get(targetDate);
        if (today == null) {
            throw new IllegalArgumentException("targetDate가 등록된 근무표 범위 밖입니다: " + targetDate);
        }
        ShiftType tomorrow = byDate.get(targetDate.plusDays(1));

        if (today != ShiftType.OFF) {
            boolean transition = tomorrow != null && tomorrow != ShiftType.OFF && tomorrow != today;
            if (transition) {
                return RoutineMode.SHIFT_TRANSITION;
            }
            return stableModeOf(today);
        }

        if (offStreakOf(schedule, targetDate).total() >= 2) {
            return RoutineMode.OFF_RECOVERY;
        }

        ShiftType nextWork = tomorrow;
        ShiftType prevWork = findPreviousWorkShiftType(schedule, targetDate);

        if (prevWork == null || nextWork == null) {
            return RoutineMode.OFF_RHYTHM_MAINTAIN;
        }
        return prevWork == nextWork ? RoutineMode.OFF_RHYTHM_MAINTAIN : RoutineMode.OFF_RHYTHM_SHIFT;
    }

    private static RoutineMode stableModeOf(ShiftType type) {
        return switch (type) {
            case DAY -> RoutineMode.DAY;
            case EVENING -> RoutineMode.EVENING;
            case NIGHT -> RoutineMode.NIGHT;
            case OFF -> throw new IllegalStateException("OFF는 stable mode가 아님");
        };
    }

    private static ShiftType findPreviousWorkShiftType(List<DaySchedule> schedule, LocalDate targetDate) {
        return schedule.stream()
                .filter(d -> d.date().isBefore(targetDate))
                .sorted(Comparator.comparing(DaySchedule::date).reversed())
                .filter(d -> d.shiftType() != ShiftType.OFF)
                .map(DaySchedule::shiftType)
                .findFirst()
                .orElse(null);
    }

    /**
     * targetDate가 속한 연속휴무 구간에서 몇 번째 날인지(1부터)와 총 휴무일수를 계산한다.
     * targetDate가 OFF가 아니면 (0, 0)을 반환한다.
     */
    public static OffStreak offStreakOf(List<DaySchedule> schedule, LocalDate targetDate) {
        Map<LocalDate, ShiftType> byDate = schedule.stream()
                .collect(Collectors.toMap(DaySchedule::date, DaySchedule::shiftType, (a, b) -> a));
        if (byDate.get(targetDate) != ShiftType.OFF) {
            return new OffStreak(0, 0);
        }
        LocalDate cursor = targetDate;
        int index = 1;
        while (byDate.get(cursor.minusDays(1)) == ShiftType.OFF) {
            cursor = cursor.minusDays(1);
            index++;
        }
        LocalDate end = targetDate;
        int total = index;
        while (byDate.get(end.plusDays(1)) == ShiftType.OFF) {
            end = end.plusDays(1);
            total++;
        }
        return new OffStreak(index, total);
    }

    public record OffStreak(int index, int total) {
    }
}

package com.shiftrhythm.backend.domain.routine;

import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.DaySchedule;
import com.shiftrhythm.backend.domain.schedule.MealBlockCalculator;
import com.shiftrhythm.backend.domain.schedule.ModeClassifier;
import com.shiftrhythm.backend.domain.schedule.RoutineMode;
import com.shiftrhythm.backend.domain.schedule.ShiftType;
import com.shiftrhythm.backend.domain.schedule.ShiftWindow;
import com.shiftrhythm.backend.domain.schedule.SleepBlock;
import com.shiftrhythm.backend.domain.schedule.SleepBlockCalculator;
import com.shiftrhythm.backend.domain.schedule.SleepBlockContext;
import com.shiftrhythm.backend.domain.schedule.SleepBlockResult;
import com.shiftrhythm.backend.domain.schedule.SleepTimeMath;
import com.shiftrhythm.backend.domain.schedule.entity.Shift;
import com.shiftrhythm.backend.domain.schedule.entity.ShiftTypeDefault;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import com.shiftrhythm.backend.domain.schedule.repository.ShiftRepository;
import com.shiftrhythm.backend.domain.schedule.repository.ShiftTypeDefaultRepository;
import com.shiftrhythm.backend.domain.schedule.repository.UserProfileRepository;
import com.shiftrhythm.backend.web.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 특정 날짜의 모드/수면블록/식사블록을 계산한다. DB 조회 + 순수 도메인 로직 호출을 잇는 접합부.
 */
@Service
public class RoutineComputationService {

    private static final int MAX_LOOKAHEAD_DAYS = 60;
    private static final int RECENT_DEFICIT_LOOKBACK_DAYS = 3;

    private final UserProfileRepository userProfileRepository;
    private final ShiftRepository shiftRepository;
    private final ShiftTypeDefaultRepository shiftTypeDefaultRepository;
    private final RoutineResultRepository routineResultRepository;

    public RoutineComputationService(UserProfileRepository userProfileRepository,
                                      ShiftRepository shiftRepository,
                                      ShiftTypeDefaultRepository shiftTypeDefaultRepository,
                                      RoutineResultRepository routineResultRepository) {
        this.userProfileRepository = userProfileRepository;
        this.shiftRepository = shiftRepository;
        this.shiftTypeDefaultRepository = shiftTypeDefaultRepository;
        this.routineResultRepository = routineResultRepository;
    }

    public RoutineComputation compute(LocalDate date) {
        return compute(date, null);
    }

    /**
     * delayMinutes가 주어지면(재설계 preview) 오늘 근무의 종료시각에 반영해서 계산한다.
     */
    public RoutineComputation compute(LocalDate date, Integer delayMinutes) {
        Long userId = CurrentUser.id();
        UserProfile profile = userProfileRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("UserProfile이 아직 등록되지 않았습니다"));

        List<DaySchedule> schedule = shiftRepository.findByUserProfileIdOrderByDateAsc(userId).stream()
                .map(s -> new DaySchedule(s.getDate(), s.getShiftType()))
                .toList();

        RoutineMode mode = ModeClassifier.classify(schedule, date);
        String modeReason = ModeClassifier.reasonOf(schedule, date);
        ModeClassifier.OffStreak offStreak = ModeClassifier.offStreakOf(schedule, date);

        ShiftWindow today = resolveWindow(userId, date);
        if (delayMinutes != null && delayMinutes != 0 && today.endTime() != null) {
            today = new ShiftWindow(today.type(), today.startTime(), today.endTime().plusMinutes(delayMinutes));
        }

        boolean stable = mode == RoutineMode.DAY || mode == RoutineMode.EVENING || mode == RoutineMode.NIGHT;
        ShiftWindow next;
        if (mode == RoutineMode.SHIFT_TRANSITION) {
            // ModeClassifier가 SHIFT_TRANSITION을 판정했다는 것 자체가 내일이 다른 유형의 근무일임을 보장한다.
            next = resolveWindow(userId, date.plusDays(1));
        } else if (stable) {
            // 안정 모드로 판정됐어도 "내일 근무일 아님/유형 다름" 케이스일 수 있다(예: 마지막 근무일 다음날 OFF).
            // 이 경우 안정 모드 산식이 기대하는 "다음 같은 유형 근무"가 없으므로 다음 근무일까지 lookahead한다.
            ShiftWindow tomorrow = resolveWindow(userId, date.plusDays(1));
            next = tomorrow.type() == today.type() ? tomorrow : resolveNextWorkWindow(userId, date.plusDays(1));
        } else {
            next = resolveNextWorkWindow(userId, date.plusDays(1));
        }
        if (next == null) {
            // 등록된 근무표 범위를 벗어나 다음 근무일을 찾을 수 없음 — 오늘과 같은 시각을 가정해 폴백
            next = today;
        }

        SleepBlock prevSleepBlock = routineResultRepository
                .findFirstByUserProfileIdAndIsCurrentTrueAndDateLessThanOrderByDateDesc(userId, date)
                .map(RoutineComputationService::toSleepBlock)
                .orElse(null);

        int recentDeficit = computeRecentSleepDeficitMinutes(userId, date, profile.getTargetSleepMinutes());

        SleepBlockContext ctx = new SleepBlockContext(
                mode, today, next,
                profile.getCommuteMinutes(), profile.getPrepMinutes(), profile.getTargetSleepMinutes(),
                profile.isNapAvailable(), profile.getNapAvailableMinutes(), profile.getRhythmPreference(),
                prevSleepBlock, offStreak.index(), offStreak.total(), recentDeficit
        );

        SleepBlockResult result = SleepBlockCalculator.calculate(ctx);
        var mealBlock = MealBlockCalculator.calculate(result.block().mainSleepStart());

        return new RoutineComputation(date, mode, modeReason, today, next, result.block(), result.window(), mealBlock, recentDeficit);
    }

    private ShiftWindow resolveWindow(Long userId, LocalDate date) {
        Shift shift = shiftRepository.findByUserProfileIdAndDate(userId, date).orElse(null);
        if (shift == null || shift.getShiftType() == ShiftType.OFF) {
            return new ShiftWindow(ShiftType.OFF, null, null);
        }
        return toWindow(userId, shift);
    }

    private ShiftWindow resolveNextWorkWindow(Long userId, LocalDate fromInclusive) {
        LocalDate cursor = fromInclusive;
        for (int i = 0; i < MAX_LOOKAHEAD_DAYS; i++) {
            Shift shift = shiftRepository.findByUserProfileIdAndDate(userId, cursor).orElse(null);
            if (shift != null && shift.getShiftType() != ShiftType.OFF) {
                return toWindow(userId, shift);
            }
            cursor = cursor.plusDays(1);
        }
        return null;
    }

    private ShiftWindow toWindow(Long userId, Shift shift) {
        LocalTime start = shift.getStartTimeOverride();
        LocalTime end = shift.getEndTimeOverride();
        if (start == null || end == null) {
            ShiftTypeDefault def = shiftTypeDefaultRepository
                    .findByUserProfileIdAndShiftType(userId, shift.getShiftType())
                    .orElseThrow(() -> new IllegalStateException("ShiftTypeDefault가 없습니다: " + shift.getShiftType()));
            if (start == null) {
                start = def.getDefaultStartTime();
            }
            if (end == null) {
                end = def.getDefaultEndTime();
            }
        }
        return new ShiftWindow(shift.getShiftType(), start, end);
    }

    private static SleepBlock toSleepBlock(RoutineResult r) {
        return new SleepBlock(r.getSleepStart(), r.getSleepEnd(),
                r.getSupplementarySleepStart(), r.getSupplementarySleepEnd(),
                r.getNapMinutes(), 60, null, null);
    }

    /**
     * DailyCheckIn에는 실제 수면시간이 직접 기록되지 않으므로, 최근 확정된 RoutineResult의
     * 계획 수면시간을 목표 대비 부족분의 근사치로 사용한다.
     */
    private int computeRecentSleepDeficitMinutes(Long userId, LocalDate date, int targetSleepMinutes) {
        List<RoutineResult> recent = routineResultRepository
                .findByUserProfileIdAndDateBetweenAndIsCurrentTrueOrderByDateAsc(
                        userId, date.minusDays(RECENT_DEFICIT_LOOKBACK_DAYS), date.minusDays(1));
        int deficit = 0;
        for (RoutineResult r : recent) {
            long duration = SleepTimeMath.minutesBetween(r.getSleepStart(), r.getSleepEnd());
            if (r.getSupplementarySleepStart() != null && r.getSupplementarySleepEnd() != null) {
                duration += SleepTimeMath.minutesBetween(r.getSupplementarySleepStart(), r.getSupplementarySleepEnd());
            }
            deficit += Math.max(0, targetSleepMinutes - (int) duration);
        }
        return deficit;
    }
}

package com.shiftrhythm.backend.domain.routine;

import com.shiftrhythm.backend.domain.ai.AiScheduleAdapter;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentRequest;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentResponse;
import com.shiftrhythm.backend.domain.checkin.entity.DailyCheckIn;
import com.shiftrhythm.backend.domain.checkin.repository.DailyCheckInRepository;
import com.shiftrhythm.backend.domain.jetlag.JetlagService;
import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.AiSleepMealValidator;
import com.shiftrhythm.backend.domain.schedule.MealBlock;
import com.shiftrhythm.backend.domain.schedule.RoutineMode;
import com.shiftrhythm.backend.domain.schedule.ShiftType;
import com.shiftrhythm.backend.domain.schedule.ShiftWindow;
import com.shiftrhythm.backend.domain.schedule.SleepBlock;
import com.shiftrhythm.backend.domain.schedule.SleepTimeMath;
import com.shiftrhythm.backend.domain.schedule.SleepWindow;
import com.shiftrhythm.backend.domain.schedule.TimelineBuilder;
import com.shiftrhythm.backend.domain.schedule.TimelineSegment;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import com.shiftrhythm.backend.domain.schedule.repository.UserProfileRepository;
import com.shiftrhythm.backend.web.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * GET /api/routines/today — 유일한 AI 재호출 판단 지점.
 */
@Service
public class RoutineFacade {

    private static final Logger log = LoggerFactory.getLogger(RoutineFacade.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int HISTORY_LOOKBACK_DAYS = 5;
    private static final int SNACK_BEFORE_SLEEP_MINUTES = 60;

    private final RoutineResultRepository routineResultRepository;
    private final DailyCheckInRepository dailyCheckInRepository;
    private final UserProfileRepository userProfileRepository;
    private final RoutineComputationService computationService;
    private final AiScheduleAdapter aiScheduleAdapter;
    private final JetlagService jetlagService;

    public RoutineFacade(RoutineResultRepository routineResultRepository,
                          DailyCheckInRepository dailyCheckInRepository,
                          UserProfileRepository userProfileRepository,
                          RoutineComputationService computationService,
                          AiScheduleAdapter aiScheduleAdapter,
                          JetlagService jetlagService) {
        this.routineResultRepository = routineResultRepository;
        this.dailyCheckInRepository = dailyCheckInRepository;
        this.userProfileRepository = userProfileRepository;
        this.computationService = computationService;
        this.aiScheduleAdapter = aiScheduleAdapter;
        this.jetlagService = jetlagService;
    }

    /**
     * "현재시각이 어느 근무 사이클에 속하는지" 계산. 자정 이후~06시 이전이고 어제가 NIGHT 사이클이면
     * 어제 날짜를 오늘 사이클로 본다(자정을 넘겨 이어지는 야간 근무/수면 대응). 정확한 산식은 별도 명세가
     * 없어 합리적으로 정한 값이다.
     */
    public LocalDate resolveCurrentCycleDate(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        if (now.toLocalTime().isBefore(LocalTime.of(6, 0))) {
            Optional<RoutineResult> yesterday = routineResultRepository
                    .findByUserProfileIdAndDateAndIsCurrentTrue(CurrentUser.id(), today.minusDays(1));
            if (yesterday.isPresent() && isOvernightCycle(yesterday.get())) {
                return today.minusDays(1);
            }
        }
        return today;
    }

    private boolean isOvernightCycle(RoutineResult r) {
        return r.getMode() == RoutineMode.NIGHT || r.getSleepEnd().isBefore(r.getSleepStart());
    }

    @Transactional
    public TodayRoutineView getToday(LocalDate date) {
        Long userId = CurrentUser.id();
        RoutineResult current = routineResultRepository.findByUserProfileIdAndDateAndIsCurrentTrue(userId, date)
                .orElseThrow(() -> new RoutineNotFoundException(date));
        UserProfile profile = userProfileRepository.findById(userId).orElseThrow();

        RoutineComputation computation = computationService.compute(date);
        Optional<DailyCheckIn> checkIn = dailyCheckInRepository.findByUserProfileIdAndDate(userId, date);

        boolean shouldRecall = checkIn.isPresent()
                && (current.getAiUpdatedAt() == null || checkIn.get().getUpdatedAt().isAfter(current.getAiUpdatedAt()));

        boolean wasJustPersonalized = false;
        if (shouldRecall) {
            SuggestAdjustmentRequest request = buildRequest(profile, computation, userId, date, checkIn.get());
            Optional<SuggestAdjustmentResponse> response = aiScheduleAdapter.suggestAdjustment(request);
            if (response.isPresent() && applyToCurrent(current, computation, response.get())) {
                current.setAiUpdatedAt(LocalDateTime.now());
                routineResultRepository.save(current);
                wasJustPersonalized = true;
            }
        }

        List<TimelineSegment> timeline = buildTimeline(current, computation.today());
        MealTimes mt = current.getMealTimes();
        var mealConstraints = new TodayRoutineView.MealConstraintsView(
                mt.bigMealCutoff(), mt.nightRestrictionStart(), mt.nightRestrictionEnd(), mt.caffeineCutoff());

        var jetlag = jetlagService.todayView(date, current.getSleepEnd(), profile.getName());
        var sleepDeficit = new TodayRoutineView.SleepDeficitView(
                computation.recentSleepDeficitMinutes(), sleepDeficitMessage(computation.recentSleepDeficitMinutes()));

        return new TodayRoutineView(date, current.getMode(), computation.modeReason(), timeline, mealConstraints,
                current.getAiReason(), wasJustPersonalized, jetlag, sleepDeficit);
    }

    private String sleepDeficitMessage(int deficitMinutes) {
        if (deficitMinutes <= 0) {
            return "최근 수면이 충분해요.";
        }
        long hours = deficitMinutes / 60;
        long minutes = deficitMinutes % 60;
        String amount = hours > 0
                ? (minutes > 0 ? "%d시간 %d분".formatted(hours, minutes) : "%d시간".formatted(hours))
                : "%d분".formatted(minutes);
        return "최근 3일간 목표보다 %s 부족하게 잤어요.".formatted(amount);
    }

    private List<TimelineSegment> buildTimeline(RoutineResult r, ShiftWindow todayShift) {
        List<TimelineSegment> segments = new ArrayList<>();
        if (todayShift.type() != ShiftType.OFF && todayShift.startTime() != null) {
            LocalTime shiftEnd = r.getAdjustedShiftEndTime() != null ? r.getAdjustedShiftEndTime() : todayShift.endTime();
            segments.add(new TimelineSegment("근무", todayShift.startTime(), shiftEnd));
        }
        segments.add(new TimelineSegment("주수면", r.getSleepStart(), r.getSleepEnd()));
        if (r.getSupplementarySleepStart() != null) {
            segments.add(new TimelineSegment("보조수면", r.getSupplementarySleepStart(), r.getSupplementarySleepEnd()));
        }
        MealTimes mt = r.getMealTimes();
        if (mt.mainMeal() != null) {
            segments.add(new TimelineSegment("주요식사", mt.mainMeal(), mt.mainMeal().plusMinutes(30)));
        }
        if (mt.subMeal() != null) {
            segments.add(new TimelineSegment("서브식사", mt.subMeal(), mt.subMeal().plusMinutes(20)));
        }
        if (mt.snackTime() != null) {
            segments.add(new TimelineSegment("간식", mt.snackTime(), mt.snackTime().plusMinutes(10)));
        }
        return TimelineBuilder.build(segments);
    }

    private SuggestAdjustmentRequest buildRequest(UserProfile profile, RoutineComputation computation,
                                                    Long userId, LocalDate date, DailyCheckIn todayCheckIn) {
        SleepBlock sb = computation.sleepBlock();
        SleepWindow window = computation.sleepWindow();
        SuggestAdjustmentRequest.SleepWindow windowDto = new SuggestAdjustmentRequest.SleepWindow(
                window.earliestSleepStart().format(TIME_FORMAT), window.latestSleepEnd().format(TIME_FORMAT));
        SuggestAdjustmentRequest.CurrentSleepBlock currentBlockDto = new SuggestAdjustmentRequest.CurrentSleepBlock(
                sb.mainSleepStart().format(TIME_FORMAT),
                sb.mainSleepEnd().format(TIME_FORMAT),
                sb.supplementarySleepStart() == null ? null : sb.supplementarySleepStart().format(TIME_FORMAT),
                sb.supplementarySleepEnd() == null ? null : sb.supplementarySleepEnd().format(TIME_FORMAT),
                sb.napMinutes(),
                sb.ankerBlockStart() == null ? null : sb.ankerBlockStart().format(TIME_FORMAT),
                sb.ankerBlockEnd() == null ? null : sb.ankerBlockEnd().format(TIME_FORMAT)
        );
        SuggestAdjustmentRequest.MealConstraints mealConstraintsDto = new SuggestAdjustmentRequest.MealConstraints(
                computation.mealBlock().bigMealCutoff().format(TIME_FORMAT),
                computation.mealBlock().nightRestrictionStart().format(TIME_FORMAT),
                computation.mealBlock().nightRestrictionEnd().format(TIME_FORMAT)
        );
        SuggestAdjustmentRequest.History history = buildHistory(userId, date, profile);
        String todayContext = todayCheckIn.getNightHungerScore() != null
                ? "야간허기점수:" + todayCheckIn.getNightHungerScore()
                : null;
        return new SuggestAdjustmentRequest(computation.mode().name(), windowDto, currentBlockDto, mealConstraintsDto, history, todayContext);
    }

    private SuggestAdjustmentRequest.History buildHistory(Long userId, LocalDate date, UserProfile profile) {
        List<DailyCheckIn> recentCheckIns = dailyCheckInRepository
                .findByUserProfileIdAndDateBetween(userId, date.minusDays(HISTORY_LOOKBACK_DAYS), date.minusDays(1));

        List<String> sleepStarts = new ArrayList<>();
        List<Integer> condition = new ArrayList<>();
        List<Integer> satisfaction = new ArrayList<>();
        List<Integer> latency = new ArrayList<>();
        List<Integer> nightHunger = new ArrayList<>();

        for (DailyCheckIn c : recentCheckIns) {
            routineResultRepository.findByUserProfileIdAndDateAndIsCurrentTrue(userId, c.getDate())
                    .ifPresent(r -> sleepStarts.add(r.getSleepStart().format(TIME_FORMAT)));
            if (c.getConditionScore() != null) {
                condition.add(c.getConditionScore());
            }
            if (c.getSleepSatisfaction() != null) {
                satisfaction.add(c.getSleepSatisfaction());
            }
            if (c.getSleepLatencyMinutes() != null) {
                latency.add(c.getSleepLatencyMinutes());
            }
            if (c.getNightHungerScore() != null) {
                nightHunger.add(c.getNightHungerScore());
            }
        }
        return new SuggestAdjustmentRequest.History(sleepStarts, condition, satisfaction, latency, nightHunger,
                profile.getRhythmPreference() == null ? null : profile.getRhythmPreference().name());
    }

    /**
     * AI 응답의 시각 문자열을 파싱해 current에 반영한다. AI가 형식이 깨진 값(예: "9:00", "24:00")이나
     * null을 보내면 LocalTime.parse가 DateTimeParseException/NullPointerException을 던지는데, 이 경우
     * 기존에 저장돼 있던 초안(current)을 그대로 두고 이번 AI 조정만 스킵한다 — 조회 API가 AI 응답 하나
     * 때문에 500으로 죽는 것을 방지한다.
     *
     * @return 정상 반영됐으면 true, 파싱 실패로 스킵했으면 false
     */
    private boolean applyToCurrent(RoutineResult current, RoutineComputation computation, SuggestAdjustmentResponse response) {
        SleepBlock clampedSleep;
        LocalTime clampedMainMeal;
        LocalTime subMeal;
        LocalTime snackTime;
        MealBlock mb = computation.mealBlock();
        try {
            SleepBlock proposedSleep = new SleepBlock(
                    LocalTime.parse(response.sleep().mainSleepStart()),
                    LocalTime.parse(response.sleep().mainSleepEnd()),
                    response.sleep().supplementarySleepStart() == null ? null : LocalTime.parse(response.sleep().supplementarySleepStart()),
                    response.sleep().supplementarySleepEnd() == null ? null : LocalTime.parse(response.sleep().supplementarySleepEnd()),
                    response.sleep().napMinutes(),
                    computation.sleepBlock().adjustToleranceMinutes(),
                    computation.sleepBlock().ankerBlockStart(),
                    computation.sleepBlock().ankerBlockEnd()
            );
            clampedSleep = AiSleepMealValidator.clampSleep(proposedSleep, computation.sleepBlock(), computation.sleepWindow());

            LocalTime mainMeal = LocalTime.parse(response.meal().mainMealTime());
            clampedMainMeal = AiSleepMealValidator.clampMainMeal(mainMeal, mb.bigMealCutoff(), mb.nightRestrictionStart(), mb.nightRestrictionEnd());
            subMeal = response.meal().subMealTime() == null ? null : LocalTime.parse(response.meal().subMealTime());
            snackTime = response.meal().snackNeeded() && response.meal().snackTime() != null
                    ? LocalTime.parse(response.meal().snackTime())
                    : null;
        } catch (DateTimeParseException | NullPointerException e) {
            log.warn("AI 응답 시각 파싱 실패, 기존 루틴을 유지하고 이번 조정은 건너뜁니다: date={}, error={}",
                    current.getDate(), e.getMessage());
            return false;
        }

        if (AiSleepMealValidator.isMainSleepSuspiciouslyShort(clampedSleep, computation.sleepBlock())) {
            log.warn("AI가 제안한 주수면이 규칙 기반 초안보다 80% 미만으로 짧아 규칙 기반 초안을 대신 사용합니다: date={}", current.getDate());
            clampedSleep = computation.sleepBlock();
        }

        if (snackTime != null) {
            long toSnack = SleepTimeMath.minutesBetween(mb.nightRestrictionEnd(), snackTime);
            long toSleepStart = SleepTimeMath.minutesBetween(mb.nightRestrictionEnd(), clampedSleep.mainSleepStart());
            if (toSnack > toSleepStart) {
                snackTime = clampedSleep.mainSleepStart().minusMinutes(SNACK_BEFORE_SLEEP_MINUTES);
            }
        }

        current.setSleepStart(clampedSleep.mainSleepStart());
        current.setSleepEnd(clampedSleep.mainSleepEnd());
        current.setSupplementarySleepStart(clampedSleep.supplementarySleepStart());
        current.setSupplementarySleepEnd(clampedSleep.supplementarySleepEnd());
        current.setNapMinutes(clampedSleep.napMinutes());
        current.setMealTimes(new MealTimes(clampedMainMeal, subMeal, snackTime, mb.bigMealCutoff(), mb.nightRestrictionStart(), mb.nightRestrictionEnd(), mb.caffeineCutoff()));
        current.setAiReason(response.sleep().reason() + " / " + response.meal().reason());
        return true;
    }
}

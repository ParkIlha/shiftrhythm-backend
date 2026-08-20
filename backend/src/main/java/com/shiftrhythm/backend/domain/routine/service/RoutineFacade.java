package com.shiftrhythm.backend.domain.routine.service;
import com.shiftrhythm.backend.domain.routine.*;

import com.shiftrhythm.backend.domain.ai.AiScheduleAdapter;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentRequest;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentResponse;
import com.shiftrhythm.backend.domain.checkin.entity.DailyCheckIn;
import com.shiftrhythm.backend.domain.checkin.repository.DailyCheckInRepository;
import com.shiftrhythm.backend.domain.jetlag.service.JetlagService;
import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.policy.AiSleepMealValidator;
import com.shiftrhythm.backend.domain.schedule.MealBlock;
import com.shiftrhythm.backend.domain.schedule.RoutineMode;
import com.shiftrhythm.backend.domain.schedule.ShiftType;
import com.shiftrhythm.backend.domain.schedule.ShiftWindow;
import com.shiftrhythm.backend.domain.schedule.SleepBlock;
import com.shiftrhythm.backend.domain.schedule.util.SleepTimeMath;
import com.shiftrhythm.backend.domain.schedule.SleepWindow;
import com.shiftrhythm.backend.domain.schedule.policy.TimelineBuilder;
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
     * "현재시각이 어느 근무 사이클에 속하는지" 계산. 타임라인이 이제 자정을 넘나드는 세그먼트를
     * 실제 LocalDateTime 그대로 들고 다니며 전날/오늘 타임라인 양쪽에 통째로 걸쳐 보여주므로
     * (TimelineBuilder 참고), 날짜 자체를 어제로 되돌리는 보정은 더 이상 필요 없다 — 항상 실제
     * 캘린더 날짜를 그대로 쓴다.
     */
    public LocalDate resolveCurrentCycleDate(LocalDateTime now) {
        return now.toLocalDate();
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

        List<TimelineSegment> timeline = buildTimeline(userId, date, current, computation.today());
        MealTimes mt = current.getMealTimes();
        var mealConstraints = new TodayRoutineView.MealConstraintsView(
                mt.bigMealCutoff(), mt.nightRestrictionStart(), mt.nightRestrictionEnd(), mt.caffeineCutoff());

        var jetlag = jetlagService.todayView(date, current.getSleepEnd().toLocalTime(), profile.getName());
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

    /**
     * targetDate 하루(00:00~24:00)의 타임라인. 자정을 넘나드는 세그먼트(예: 어제 밤 수면이 오늘
     * 새벽까지 이어짐)를 놓치지 않기 위해, 어제 것까지 후보로 모아서 넘긴다 — 내일 것까지 볼 필요는
     * 없다(TimelineSegment.date는 항상 시작일이라, 내일 날짜로 시작하는 세그먼트는 정의상 오늘 00:00
     * 이전으로 넘어올 수 없다).
     */
    private List<TimelineSegment> buildTimeline(Long userId, LocalDate date, RoutineResult current, ShiftWindow todayShift) {
        List<TimelineSegment> candidates = new ArrayList<>(daySegments(current, todayShift, date));

        LocalDate yesterday = date.minusDays(1);
        routineResultRepository.findByUserProfileIdAndDateAndIsCurrentTrue(userId, yesterday)
                .ifPresent(prev -> {
                    ShiftWindow prevShift = computationService.compute(yesterday).today();
                    candidates.addAll(daySegments(prev, prevShift, yesterday));
                });

        return TimelineBuilder.buildForDay(candidates, date);
    }

    private List<TimelineSegment> daySegments(RoutineResult r, ShiftWindow shift, LocalDate date) {
        List<TimelineSegment> segments = new ArrayList<>();
        if (shift.type() != ShiftType.OFF && shift.startTime() != null) {
            LocalDateTime shiftEnd = r.getAdjustedShiftEndTime() != null ? r.getAdjustedShiftEndTime() : shift.endTime();
            segments.add(new TimelineSegment("근무", shift.startTime(), shiftEnd));
        }
        segments.add(new TimelineSegment("주수면", r.getSleepStart(), r.getSleepEnd()));
        if (r.getSupplementarySleepStart() != null) {
            segments.add(new TimelineSegment("보조수면", r.getSupplementarySleepStart(), r.getSupplementarySleepEnd()));
        }
        // 식사는 여전히 날짜 없는 벽시계 시각(MealTimes)으로만 저장되므로, 이 RoutineResult가 속한
        // 등록 날짜(date)를 그대로 붙인다 — 근무/수면과 달리 자정을 넘겨 다음날로 미뤄지는 경우가 없다.
        MealTimes mt = r.getMealTimes();
        if (mt.mainMeal() != null) {
            LocalDateTime start = LocalDateTime.of(date, mt.mainMeal());
            segments.add(new TimelineSegment("주요식사", start, start.plusMinutes(30)));
        }
        if (mt.subMeal() != null) {
            LocalDateTime start = LocalDateTime.of(date, mt.subMeal());
            segments.add(new TimelineSegment("서브식사", start, start.plusMinutes(20)));
        }
        if (mt.snackTime() != null) {
            LocalDateTime start = LocalDateTime.of(date, mt.snackTime());
            segments.add(new TimelineSegment("간식", start, start.plusMinutes(10)));
        }
        return segments;
    }

    private SuggestAdjustmentRequest buildRequest(UserProfile profile, RoutineComputation computation,
                                                    Long userId, LocalDate date, DailyCheckIn todayCheckIn) {
        SleepBlock sb = computation.sleepBlock();
        SleepWindow window = computation.sleepWindow();
        SuggestAdjustmentRequest.SleepWindow windowDto = new SuggestAdjustmentRequest.SleepWindow(
                window.earliestSleepStart().toLocalTime().format(TIME_FORMAT), window.latestSleepEnd().toLocalTime().format(TIME_FORMAT));
        SuggestAdjustmentRequest.CurrentSleepBlock currentBlockDto = new SuggestAdjustmentRequest.CurrentSleepBlock(
                sb.mainSleepStart().toLocalTime().format(TIME_FORMAT),
                sb.mainSleepEnd().toLocalTime().format(TIME_FORMAT),
                sb.supplementarySleepStart() == null ? null : sb.supplementarySleepStart().toLocalTime().format(TIME_FORMAT),
                sb.supplementarySleepEnd() == null ? null : sb.supplementarySleepEnd().toLocalTime().format(TIME_FORMAT),
                sb.napMinutes(),
                sb.ankerBlockStart() == null ? null : sb.ankerBlockStart().toLocalTime().format(TIME_FORMAT),
                sb.ankerBlockEnd() == null ? null : sb.ankerBlockEnd().toLocalTime().format(TIME_FORMAT)
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
            SleepWindow window = computation.sleepWindow();
            SleepBlock proposedSleep = new SleepBlock(
                    SleepTimeMath.anchorWithinWindow(LocalTime.parse(response.sleep().mainSleepStart()), window.earliestSleepStart(), window.latestSleepEnd()),
                    SleepTimeMath.anchorWithinWindow(LocalTime.parse(response.sleep().mainSleepEnd()), window.earliestSleepStart(), window.latestSleepEnd()),
                    response.sleep().supplementarySleepStart() == null ? null
                            : SleepTimeMath.anchorWithinWindow(LocalTime.parse(response.sleep().supplementarySleepStart()), window.earliestSleepStart(), window.latestSleepEnd()),
                    response.sleep().supplementarySleepEnd() == null ? null
                            : SleepTimeMath.anchorWithinWindow(LocalTime.parse(response.sleep().supplementarySleepEnd()), window.earliestSleepStart(), window.latestSleepEnd()),
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
            long toSleepStart = SleepTimeMath.minutesBetween(mb.nightRestrictionEnd(), clampedSleep.mainSleepStart().toLocalTime());
            if (toSnack > toSleepStart) {
                snackTime = clampedSleep.mainSleepStart().toLocalTime().minusMinutes(SNACK_BEFORE_SLEEP_MINUTES);
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

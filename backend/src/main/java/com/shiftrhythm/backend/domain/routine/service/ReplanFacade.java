package com.shiftrhythm.backend.domain.routine.service;
import com.shiftrhythm.backend.domain.routine.*;

import com.shiftrhythm.backend.domain.ai.AiScheduleAdapter;
import com.shiftrhythm.backend.domain.ai.dto.ParseDisruptionRequest;
import com.shiftrhythm.backend.domain.ai.dto.ParseDisruptionResponse;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentRequest;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentResponse;
import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.policy.AiSleepMealValidator;
import com.shiftrhythm.backend.domain.schedule.policy.MealBlockCalculator;
import com.shiftrhythm.backend.domain.schedule.util.AppClock;
import com.shiftrhythm.backend.domain.schedule.MealBlock;
import com.shiftrhythm.backend.domain.schedule.RoutineMode;
import com.shiftrhythm.backend.domain.schedule.SleepBlock;
import com.shiftrhythm.backend.domain.schedule.util.SleepTimeMath;
import com.shiftrhythm.backend.domain.schedule.SleepWindow;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import com.shiftrhythm.backend.domain.schedule.repository.UserProfileRepository;
import com.shiftrhythm.backend.web.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 재설계 preview/confirm. preview 결과는 DB에 저장하지 않고 5분 TTL 인메모리 캐시에 둔다.
 */
@Service
public class ReplanFacade {

    private static final Logger log = LoggerFactory.getLogger(ReplanFacade.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final long TTL_MINUTES = 5;
    private static final String NIGHT_RESTRICTION_START = "00:00";
    private static final String NIGHT_RESTRICTION_END = "06:00";

    private final AiScheduleAdapter aiScheduleAdapter;
    private final RoutineComputationService computationService;
    private final RoutineResultRepository routineResultRepository;
    private final UserProfileRepository userProfileRepository;
    private final RoutineFacade routineFacade;

    private final Map<UUID, CachedPreview> previews = new ConcurrentHashMap<>();

    public ReplanFacade(AiScheduleAdapter aiScheduleAdapter,
                         RoutineComputationService computationService,
                         RoutineResultRepository routineResultRepository,
                         UserProfileRepository userProfileRepository,
                         RoutineFacade routineFacade) {
        this.aiScheduleAdapter = aiScheduleAdapter;
        this.computationService = computationService;
        this.routineResultRepository = routineResultRepository;
        this.userProfileRepository = userProfileRepository;
        this.routineFacade = routineFacade;
    }

    private static final java.util.Set<String> SHIFT_BLOCK_CHANGING_EVENT_TYPES = java.util.Set.of("SHIFT_END_DELAY", "SHIFT_ADDED");

    private record CachedPreview(LocalDate date, RoutineMode mode, SleepBlock finalSleep, MealTimes finalMeal,
                                  LocalDateTime adjustedShiftEndTime,
                                  ParseDisruptionResponse disruption, SuggestAdjustmentResponse suggestion,
                                  Instant expiresAt) {
    }

    public record RoutineSnapshot(String mode, String sleepStart, String sleepEnd, String mainMeal1, String mainMeal2) {
    }

    public record PreviewResult(UUID previewId, String eventType, String reasonCategory,
                                 RoutineSnapshot before, RoutineSnapshot after, String aiReason) {
    }

    public record ConfirmResult(boolean ok, LocalDate date, int version) {
    }

    @Transactional
    public PreviewResult preview(String rawText) {
        Long userId = CurrentUser.id();
        LocalDate date = routineFacade.resolveCurrentCycleDate(AppClock.now());
        RoutineResult current = routineResultRepository.findByUserProfileIdAndDateAndIsCurrentTrue(userId, date)
                .orElseThrow(() -> new RoutineNotFoundException(date));
        UserProfile profile = userProfileRepository.findById(userId).orElseThrow();

        RoutineComputation baseline = computationService.compute(date);
        ParseDisruptionRequest.ShiftContext ctx = new ParseDisruptionRequest.ShiftContext(
                baseline.today().type().name(),
                baseline.next() == null ? null : baseline.next().type().name(),
                baseline.today().endTime() == null ? null : baseline.today().endTime().toLocalTime().format(TIME_FORMAT)
        );
        ParseDisruptionResponse disruption = aiScheduleAdapter.parseDisruption(new ParseDisruptionRequest(rawText, ctx))
                .orElseThrow(ParseFailedException::new);

        RoutineComputation recomputed = computationService.compute(date, disruption.delayMinutes());

        SuggestAdjustmentRequest suggestRequest = buildSuggestRequest(profile, recomputed);
        SuggestAdjustmentResponse suggestion = aiScheduleAdapter.suggestAdjustment(suggestRequest).orElse(null);

        SleepBlock finalSleep = resolveFinalSleep(recomputed, suggestion);
        MealTimes finalMeal = resolveFinalMeal(suggestion, finalSleep);

        LocalDateTime adjustedShiftEndTime = SHIFT_BLOCK_CHANGING_EVENT_TYPES.contains(disruption.eventType())
                ? recomputed.today().endTime()
                : null;

        UUID previewId = UUID.randomUUID();
        previews.put(previewId, new CachedPreview(date, recomputed.mode(), finalSleep, finalMeal, adjustedShiftEndTime,
                disruption, suggestion, Instant.now().plus(TTL_MINUTES, ChronoUnit.MINUTES)));

        RoutineSnapshot before = snapshotOf(current.getMode().name(), current.getSleepStart().toLocalTime(), current.getSleepEnd().toLocalTime(), current.getMealTimes());
        RoutineSnapshot after = snapshotOf(recomputed.mode().name(), finalSleep.mainSleepStart().toLocalTime(), finalSleep.mainSleepEnd().toLocalTime(), finalMeal);
        String aiReason = suggestion == null ? null : (suggestion.sleep().reason() + " / " + suggestion.meal().reason());

        return new PreviewResult(previewId, disruption.eventType(), disruption.reasonCategory(), before, after, aiReason);
    }

    @Transactional
    public ConfirmResult confirm(UUID previewId) {
        CachedPreview cached = previews.remove(previewId);
        if (cached == null || cached.expiresAt().isBefore(Instant.now())) {
            throw new PreviewExpiredException();
        }

        Long userId = CurrentUser.id();
        RoutineResult prevCurrent = routineResultRepository
                .findByUserProfileIdAndDateAndIsCurrentTrue(userId, cached.date())
                .orElseThrow(() -> new RoutineNotFoundException(cached.date()));
        prevCurrent.setCurrent(false);
        routineResultRepository.save(prevCurrent);

        int newVersion = routineResultRepository.findFirstByUserProfileIdAndDateOrderByVersionDesc(userId, cached.date())
                .map(r -> r.getVersion() + 1)
                .orElse(prevCurrent.getVersion() + 1);

        SleepBlock sb = cached.finalSleep();
        RoutineResult next = new RoutineResult(userId, cached.date(), newVersion, true,
                parseReplanReason(cached.disruption().reasonCategory()), cached.mode(),
                sb.mainSleepStart(), sb.mainSleepEnd(), sb.supplementarySleepStart(), sb.supplementarySleepEnd(),
                sb.napMinutes(), cached.finalMeal());
        next.setAdjustedShiftEndTime(cached.adjustedShiftEndTime());
        next.setAiReason(cached.suggestion() == null ? null
                : cached.suggestion().sleep().reason() + " / " + cached.suggestion().meal().reason());
        next.setAiUpdatedAt(LocalDateTime.now());
        routineResultRepository.save(next);

        return new ConfirmResult(true, cached.date(), newVersion);
    }

    /**
     * AI가 ReplanReason enum에 없는 값을 보내면(계약 드리프트) valueOf가 IllegalArgumentException을
     * 던져 confirm 전체가 500으로 죽는다. AI 응답 파싱 실패를 폴백으로 흡수하는 다른 지점들과 동일하게,
     * 알 수 없는 값은 OTHER로 떨어뜨리고 로그만 남긴다.
     */
    private ReplanReason parseReplanReason(String reasonCategory) {
        try {
            return ReplanReason.valueOf(reasonCategory);
        } catch (IllegalArgumentException | NullPointerException e) {
            log.warn("AI가 알 수 없는 reasonCategory를 반환해 OTHER로 대체합니다: {}", reasonCategory);
            return ReplanReason.OTHER;
        }
    }

    private SuggestAdjustmentRequest buildSuggestRequest(UserProfile profile, RoutineComputation computation) {
        SleepBlock sb = computation.sleepBlock();
        SuggestAdjustmentRequest.SleepWindow windowDto = new SuggestAdjustmentRequest.SleepWindow(
                computation.sleepWindow().earliestSleepStart().toLocalTime().format(TIME_FORMAT),
                computation.sleepWindow().latestSleepEnd().toLocalTime().format(TIME_FORMAT));
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
                computation.mealBlock().bigMealCutoff().toLocalTime().format(TIME_FORMAT),
                NIGHT_RESTRICTION_START, NIGHT_RESTRICTION_END
        );
        SuggestAdjustmentRequest.History history = new SuggestAdjustmentRequest.History(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                profile.getRhythmPreference() == null ? null : profile.getRhythmPreference().name());
        return new SuggestAdjustmentRequest(computation.mode().name(), windowDto, currentBlockDto, mealConstraintsDto, history, null);
    }

    private SleepBlock resolveFinalSleep(RoutineComputation computation, SuggestAdjustmentResponse suggestion) {
        if (suggestion == null) {
            return computation.sleepBlock();
        }
        SleepWindow window = computation.sleepWindow();
        SleepBlock proposed = new SleepBlock(
                SleepTimeMath.anchorWithinWindow(LocalTime.parse(suggestion.sleep().mainSleepStart()), window.earliestSleepStart(), window.latestSleepEnd()),
                SleepTimeMath.anchorWithinWindow(LocalTime.parse(suggestion.sleep().mainSleepEnd()), window.earliestSleepStart(), window.latestSleepEnd()),
                suggestion.sleep().supplementarySleepStart() == null ? null
                        : SleepTimeMath.anchorWithinWindow(LocalTime.parse(suggestion.sleep().supplementarySleepStart()), window.earliestSleepStart(), window.latestSleepEnd()),
                suggestion.sleep().supplementarySleepEnd() == null ? null
                        : SleepTimeMath.anchorWithinWindow(LocalTime.parse(suggestion.sleep().supplementarySleepEnd()), window.earliestSleepStart(), window.latestSleepEnd()),
                suggestion.sleep().napMinutes(),
                computation.sleepBlock().adjustToleranceMinutes(),
                computation.sleepBlock().ankerBlockStart(),
                computation.sleepBlock().ankerBlockEnd()
        );
        SleepBlock clamped = AiSleepMealValidator.clampSleep(proposed, computation.sleepBlock(), computation.sleepWindow());
        if (AiSleepMealValidator.isMainSleepSuspiciouslyShort(clamped, computation.sleepBlock())) {
            return computation.sleepBlock();
        }
        return clamped;
    }

    /**
     * 식사 clamp는 확정된 최종 수면(finalSleep) 기준이어야 한다 — recomputed.mealBlock()은 규칙 기반
     * 초안(AI 조정 전) 수면 기준이라, 실제로 저장될 수면 시각과 다를 수 있기 때문.
     */
    private MealTimes resolveFinalMeal(SuggestAdjustmentResponse suggestion, SleepBlock finalSleep) {
        MealBlock finalMealBlock = MealBlockCalculator.calculate(finalSleep);
        if (suggestion == null) {
            LocalTime cutoff = finalMealBlock.bigMealCutoff().toLocalTime();
            return new MealTimes(cutoff, cutoff, null, cutoff, finalMealBlock.caffeineCutoff().toLocalTime());
        }
        LocalTime mainMeal1 = AiSleepMealValidator.clampMeal(
                LocalTime.parse(suggestion.meal().mainMealTime()), finalMealBlock, true);
        // TODO(ai-server mealTime2 필수화 시 제거): mainMeal2는 항상 필수인데 ai-server 계약은
        // 아직 subMealTime을 선택값으로 취급한다 — AI가 안 주면 안전값(bigMealCutoff)으로 채우는
        // 임시방편. ai-server가 mealTime2를 필수 응답으로 바꾸면 이 대체 로직을 지우고
        // null이면 이번 AI 응답 전체를 실패 취급(기존 값 유지)하도록 바꿀 것.
        LocalTime mainMeal2Candidate = suggestion.meal().subMealTime() == null
                ? finalMealBlock.bigMealCutoff().toLocalTime()
                : LocalTime.parse(suggestion.meal().subMealTime());
        LocalTime mainMeal2 = AiSleepMealValidator.clampMeal(mainMeal2Candidate, finalMealBlock, true);
        if (AiSleepMealValidator.mealsCollapsed(mainMeal1, mainMeal2)) {
            log.warn("clamp 이후 mainMeal1/mainMeal2가 30분 이내로 붙었습니다(사실상 한 끼로 뭉개짐 가능): "
                    + "date={}, mainMeal1={}, mainMeal2={}", finalSleep.mainSleepStart().toLocalDate(), mainMeal1, mainMeal2);
        }
        LocalTime snackCandidate = suggestion.meal().snackNeeded() && suggestion.meal().snackTime() != null
                ? LocalTime.parse(suggestion.meal().snackTime())
                : null;
        LocalTime snackTime = snackCandidate == null ? null : AiSleepMealValidator.clampMeal(snackCandidate, finalMealBlock, false);
        return new MealTimes(mainMeal1, mainMeal2, snackTime,
                finalMealBlock.bigMealCutoff().toLocalTime(), finalMealBlock.caffeineCutoff().toLocalTime());
    }

    private RoutineSnapshot snapshotOf(String mode, LocalTime sleepStart, LocalTime sleepEnd, MealTimes mealTimes) {
        return new RoutineSnapshot(mode, sleepStart.format(TIME_FORMAT), sleepEnd.format(TIME_FORMAT),
                mealTimes.mainMeal1() == null ? null : mealTimes.mainMeal1().format(TIME_FORMAT),
                mealTimes.mainMeal2() == null ? null : mealTimes.mainMeal2().format(TIME_FORMAT));
    }
}

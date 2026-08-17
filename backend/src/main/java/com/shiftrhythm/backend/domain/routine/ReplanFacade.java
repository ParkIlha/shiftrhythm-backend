package com.shiftrhythm.backend.domain.routine;

import com.shiftrhythm.backend.domain.ai.AiScheduleAdapter;
import com.shiftrhythm.backend.domain.ai.dto.ParseDisruptionRequest;
import com.shiftrhythm.backend.domain.ai.dto.ParseDisruptionResponse;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentRequest;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentResponse;
import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.AiSleepMealValidator;
import com.shiftrhythm.backend.domain.schedule.MealBlock;
import com.shiftrhythm.backend.domain.schedule.RoutineMode;
import com.shiftrhythm.backend.domain.schedule.SleepBlock;
import com.shiftrhythm.backend.domain.schedule.SleepTimeMath;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import com.shiftrhythm.backend.domain.schedule.repository.UserProfileRepository;
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

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final long TTL_MINUTES = 5;
    private static final int SNACK_BEFORE_SLEEP_MINUTES = 60;

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

    private record CachedPreview(LocalDate date, RoutineMode mode, SleepBlock finalSleep, MealTimes finalMeal,
                                  ParseDisruptionResponse disruption, SuggestAdjustmentResponse suggestion,
                                  Instant expiresAt) {
    }

    public record RoutineSnapshot(String mode, String sleepStart, String sleepEnd, String mainMeal, String subMeal) {
    }

    public record PreviewResult(UUID previewId, String eventType, String reasonCategory,
                                 RoutineSnapshot before, RoutineSnapshot after, String aiReason) {
    }

    public record ConfirmResult(boolean ok, LocalDate date, int version) {
    }

    @Transactional
    public PreviewResult preview(String rawText) {
        Long userId = UserProfile.SINGLETON_ID;
        LocalDate date = routineFacade.resolveCurrentCycleDate(LocalDateTime.now());
        RoutineResult current = routineResultRepository.findByUserProfileIdAndDateAndIsCurrentTrue(userId, date)
                .orElseThrow(() -> new RoutineNotFoundException(date));
        UserProfile profile = userProfileRepository.findById(userId).orElseThrow();

        RoutineComputation baseline = computationService.compute(date);
        ParseDisruptionRequest.ShiftContext ctx = new ParseDisruptionRequest.ShiftContext(
                baseline.today().type().name(),
                baseline.next() == null ? null : baseline.next().type().name(),
                baseline.today().endTime() == null ? null : baseline.today().endTime().format(TIME_FORMAT)
        );
        ParseDisruptionResponse disruption = aiScheduleAdapter.parseDisruption(new ParseDisruptionRequest(rawText, ctx))
                .orElseThrow(ParseFailedException::new);

        RoutineComputation recomputed = computationService.compute(date, disruption.delayMinutes());

        SuggestAdjustmentRequest suggestRequest = buildSuggestRequest(profile, recomputed);
        SuggestAdjustmentResponse suggestion = aiScheduleAdapter.suggestAdjustment(suggestRequest).orElse(null);

        SleepBlock finalSleep = resolveFinalSleep(recomputed, suggestion);
        MealTimes finalMeal = resolveFinalMeal(recomputed.mealBlock(), suggestion, finalSleep);

        UUID previewId = UUID.randomUUID();
        previews.put(previewId, new CachedPreview(date, recomputed.mode(), finalSleep, finalMeal, disruption, suggestion,
                Instant.now().plus(TTL_MINUTES, ChronoUnit.MINUTES)));

        RoutineSnapshot before = snapshotOf(current.getMode().name(), current.getSleepStart(), current.getSleepEnd(), current.getMealTimes());
        RoutineSnapshot after = snapshotOf(recomputed.mode().name(), finalSleep.mainSleepStart(), finalSleep.mainSleepEnd(), finalMeal);
        String aiReason = suggestion == null ? null : (suggestion.sleep().reason() + " / " + suggestion.meal().reason());

        return new PreviewResult(previewId, disruption.eventType(), disruption.reasonCategory(), before, after, aiReason);
    }

    @Transactional
    public ConfirmResult confirm(UUID previewId) {
        CachedPreview cached = previews.remove(previewId);
        if (cached == null || cached.expiresAt().isBefore(Instant.now())) {
            throw new PreviewExpiredException();
        }

        Long userId = UserProfile.SINGLETON_ID;
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
                ReplanReason.valueOf(cached.disruption().reasonCategory()), cached.mode(),
                sb.mainSleepStart(), sb.mainSleepEnd(), sb.supplementarySleepStart(), sb.supplementarySleepEnd(),
                sb.napMinutes(), cached.finalMeal());
        next.setAiReason(cached.suggestion() == null ? null
                : cached.suggestion().sleep().reason() + " / " + cached.suggestion().meal().reason());
        next.setAiUpdatedAt(LocalDateTime.now());
        routineResultRepository.save(next);

        return new ConfirmResult(true, cached.date(), newVersion);
    }

    private SuggestAdjustmentRequest buildSuggestRequest(UserProfile profile, RoutineComputation computation) {
        SleepBlock sb = computation.sleepBlock();
        SuggestAdjustmentRequest.SleepWindow windowDto = new SuggestAdjustmentRequest.SleepWindow(
                computation.sleepWindow().earliestSleepStart().format(TIME_FORMAT),
                computation.sleepWindow().latestSleepEnd().format(TIME_FORMAT));
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
        SuggestAdjustmentRequest.History history = new SuggestAdjustmentRequest.History(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                profile.getRhythmPreference() == null ? null : profile.getRhythmPreference().name());
        return new SuggestAdjustmentRequest(computation.mode().name(), windowDto, currentBlockDto, mealConstraintsDto, history, null);
    }

    private SleepBlock resolveFinalSleep(RoutineComputation computation, SuggestAdjustmentResponse suggestion) {
        if (suggestion == null) {
            return computation.sleepBlock();
        }
        SleepBlock proposed = new SleepBlock(
                LocalTime.parse(suggestion.sleep().mainSleepStart()),
                LocalTime.parse(suggestion.sleep().mainSleepEnd()),
                suggestion.sleep().supplementarySleepStart() == null ? null : LocalTime.parse(suggestion.sleep().supplementarySleepStart()),
                suggestion.sleep().supplementarySleepEnd() == null ? null : LocalTime.parse(suggestion.sleep().supplementarySleepEnd()),
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

    private MealTimes resolveFinalMeal(MealBlock mealBlock, SuggestAdjustmentResponse suggestion, SleepBlock finalSleep) {
        if (suggestion == null) {
            return new MealTimes(mealBlock.bigMealCutoff(), null, null, mealBlock.bigMealCutoff(), mealBlock.nightRestrictionStart(), mealBlock.nightRestrictionEnd());
        }
        LocalTime mainMeal = AiSleepMealValidator.clampMainMeal(
                LocalTime.parse(suggestion.meal().mainMealTime()),
                mealBlock.bigMealCutoff(), mealBlock.nightRestrictionStart(), mealBlock.nightRestrictionEnd());
        LocalTime subMeal = suggestion.meal().subMealTime() == null ? null : LocalTime.parse(suggestion.meal().subMealTime());
        LocalTime snackTime = suggestion.meal().snackNeeded() && suggestion.meal().snackTime() != null
                ? LocalTime.parse(suggestion.meal().snackTime())
                : null;
        if (snackTime != null) {
            long toSnack = SleepTimeMath.minutesBetween(mealBlock.nightRestrictionEnd(), snackTime);
            long toSleepStart = SleepTimeMath.minutesBetween(mealBlock.nightRestrictionEnd(), finalSleep.mainSleepStart());
            if (toSnack > toSleepStart) {
                snackTime = finalSleep.mainSleepStart().minusMinutes(SNACK_BEFORE_SLEEP_MINUTES);
            }
        }
        return new MealTimes(mainMeal, subMeal, snackTime, mealBlock.bigMealCutoff(), mealBlock.nightRestrictionStart(), mealBlock.nightRestrictionEnd());
    }

    private RoutineSnapshot snapshotOf(String mode, LocalTime sleepStart, LocalTime sleepEnd, MealTimes mealTimes) {
        return new RoutineSnapshot(mode, sleepStart.format(TIME_FORMAT), sleepEnd.format(TIME_FORMAT),
                mealTimes.mainMeal() == null ? null : mealTimes.mainMeal().format(TIME_FORMAT),
                mealTimes.subMeal() == null ? null : mealTimes.subMeal().format(TIME_FORMAT));
    }
}

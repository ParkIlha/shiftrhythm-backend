package com.shiftrhythm.backend.domain.routine;

import com.shiftrhythm.backend.domain.ai.AiScheduleAdapter;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentRequest;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentResponse;
import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.AiSleepMealValidator;
import com.shiftrhythm.backend.domain.schedule.MealBlock;
import com.shiftrhythm.backend.domain.schedule.RhythmPreference;
import com.shiftrhythm.backend.domain.schedule.ShiftNotFoundException;
import com.shiftrhythm.backend.domain.schedule.ShiftType;
import com.shiftrhythm.backend.domain.schedule.SleepBlock;
import com.shiftrhythm.backend.domain.schedule.SleepTimeMath;
import com.shiftrhythm.backend.domain.schedule.SleepWindow;
import com.shiftrhythm.backend.domain.schedule.entity.Shift;
import com.shiftrhythm.backend.domain.schedule.entity.ShiftTypeDefault;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import com.shiftrhythm.backend.domain.schedule.repository.ShiftRepository;
import com.shiftrhythm.backend.domain.schedule.repository.ShiftTypeDefaultRepository;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 온보딩(개인화 설정 등록 + 근무표 등록) 서비스. 근무표 등록 시 명세 5-1 파이프라인을 수행한다:
 * 1) 날짜순으로 Mode+SleepBlock(+SleepWindow) 규칙 기반 초안 계산 → RoutineResult(version=1) 저장
 *    (다음 날짜 계산의 prevSleepBlock으로 쓰이도록 즉시 flush)
 * 2) 시그니처(mode, todayType, nextType, napAvailable)별로 묶어 suggest-adjustment 1회 호출
 *    — AI가 window 안에서 수면·식사 절대시각을 자유롭게 재배치, Spring이 clamp
 * 3) 같은 시그니처의 모든 날짜에 결과 일괄 적용
 */
@Service
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final int SNACK_BEFORE_SLEEP_MINUTES = 60;

    private final UserProfileRepository userProfileRepository;
    private final ShiftTypeDefaultRepository shiftTypeDefaultRepository;
    private final ShiftRepository shiftRepository;
    private final RoutineResultRepository routineResultRepository;
    private final RoutineComputationService computationService;
    private final AiScheduleAdapter aiScheduleAdapter;

    public OnboardingService(UserProfileRepository userProfileRepository,
                              ShiftTypeDefaultRepository shiftTypeDefaultRepository,
                              ShiftRepository shiftRepository,
                              RoutineResultRepository routineResultRepository,
                              RoutineComputationService computationService,
                              AiScheduleAdapter aiScheduleAdapter) {
        this.userProfileRepository = userProfileRepository;
        this.shiftTypeDefaultRepository = shiftTypeDefaultRepository;
        this.shiftRepository = shiftRepository;
        this.routineResultRepository = routineResultRepository;
        this.computationService = computationService;
        this.aiScheduleAdapter = aiScheduleAdapter;
    }

    public record ShiftTypeDefaultInput(ShiftType shiftType, LocalTime startTime, LocalTime endTime) {
    }

    public record ShiftInput(LocalDate date, ShiftType shiftType) {
    }

    /** 마이페이지용 조회. 아직 온보딩 전이면 빈 값. */
    @Transactional(readOnly = true)
    public Optional<UserProfile> findProfile() {
        return userProfileRepository.findById(CurrentUser.id());
    }

    /**
     * X-User-Id 헤더가 없으면 새 사용자를 발급한다("새로 시작하기"). 헤더가 있으면 그 프로필을 덮어쓴다
     * (마이페이지 수정). 저장된 프로필의 id를 반환하니 컨트롤러가 그대로 클라이언트에 내려주면 된다.
     */
    @Transactional
    public UserProfile upsertProfile(String name, int commuteMinutes, int prepMinutes, int targetSleepMinutes,
                                      boolean napAvailable, Integer napAvailableMinutes,
                                      RhythmPreference rhythmPreference) {
        Long userId = CurrentUser.idOrNull();
        UserProfile profile = userId == null
                ? new UserProfile()
                : userProfileRepository.findById(userId).orElseGet(UserProfile::new);
        profile.setName(name);
        profile.setCommuteMinutes(commuteMinutes);
        profile.setPrepMinutes(prepMinutes);
        profile.setTargetSleepMinutes(targetSleepMinutes);
        profile.setNapAvailable(napAvailable);
        profile.setNapAvailableMinutes(napAvailableMinutes);
        profile.setRhythmPreference(rhythmPreference);
        return userProfileRepository.save(profile);
    }

    /**
     * 근무유형별 기본 시작/종료 시각(defaults)은 AI(parse-schedule)가 사진에서 읽어준 shiftTypes를
     * 사용자가 검토/보정한 최종본이다 — 온보딩 1단계(개인화 데이터)가 아니라 여기서 함께 확정한다.
     */
    @Transactional
    public void registerSchedule(List<ShiftTypeDefaultInput> defaults, List<ShiftInput> shifts) {
        Long userId = CurrentUser.id();
        UserProfile profile = userProfileRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("프로필을 먼저 등록해야 합니다"));

        shiftTypeDefaultRepository.deleteByUserProfileId(userId);
        shiftTypeDefaultRepository.flush();
        for (ShiftTypeDefaultInput d : defaults) {
            shiftTypeDefaultRepository.save(new ShiftTypeDefault(userId, d.shiftType(), d.startTime(), d.endTime()));
        }
        shiftTypeDefaultRepository.flush();

        shiftRepository.deleteByUserProfileId(userId);
        shiftRepository.flush();

        List<ShiftInput> sorted = shifts.stream().sorted(Comparator.comparing(ShiftInput::date)).toList();

        for (ShiftInput s : sorted) {
            shiftRepository.save(new Shift(userId, s.date(), s.shiftType(), null, null));
        }
        shiftRepository.flush();

        List<RoutineResult> created = new ArrayList<>();
        Map<LocalDate, RoutineComputation> computationsByDate = new LinkedHashMap<>();
        Map<RoutineComputation.RoutineSignature, RoutineComputation> representativeBySignature = new LinkedHashMap<>();

        for (ShiftInput s : sorted) {
            RoutineComputation computation = computationService.compute(s.date());
            computationsByDate.put(s.date(), computation);
            representativeBySignature.putIfAbsent(computation.signature(profile.isNapAvailable()), computation);

            SleepBlock sb = computation.sleepBlock();
            MealBlock mb = computation.mealBlock();

            // 이미 등록된 날짜(재온보딩/근무표 갱신)면 새 version=1을 또 insert하려다 유니크 제약
            // (user_profile_id, date, version) 위반으로 500이 나던 문제 — 기존 current를 내리고
            // 다음 version으로 upsert한다. editShift의 recomputeDay와 동일한 패턴.
            Optional<RoutineResult> existingCurrent = routineResultRepository
                    .findByUserProfileIdAndDateAndIsCurrentTrue(userId, s.date());
            int newVersion = routineResultRepository.findFirstByUserProfileIdAndDateOrderByVersionDesc(userId, s.date())
                    .map(r -> r.getVersion() + 1)
                    .orElse(1);
            ReplanReason reason = existingCurrent.isPresent() ? ReplanReason.SHIFT_CHANGE : null;
            existingCurrent.ifPresent(prev -> {
                prev.setCurrent(false);
                routineResultRepository.save(prev);
            });

            RoutineResult result = new RoutineResult(userId, s.date(), newVersion, true, reason, computation.mode(),
                    sb.mainSleepStart(), sb.mainSleepEnd(), sb.supplementarySleepStart(), sb.supplementarySleepEnd(),
                    sb.napMinutes(), new MealTimes(null, null, null, mb.bigMealCutoff(), mb.nightRestrictionStart(), mb.nightRestrictionEnd(), mb.caffeineCutoff()));
            routineResultRepository.save(result);
            routineResultRepository.flush();
            created.add(result);
        }

        for (var entry : representativeBySignature.entrySet()) {
            RoutineComputation.RoutineSignature signature = entry.getKey();
            RoutineComputation representative = entry.getValue();

            SuggestAdjustmentRequest request = buildSuggestRequest(profile, representative, emptyHistory(profile), null);
            SuggestAdjustmentResponse response = aiScheduleAdapter.suggestAdjustment(request).orElse(null);

            for (RoutineResult r : created) {
                RoutineComputation computation = computationsByDate.get(r.getDate());
                if (!computation.signature(profile.isNapAvailable()).equals(signature)) {
                    continue;
                }
                applyResponse(r, response, computation);
                routineResultRepository.save(r);
            }
        }
    }

    /** 등록된 근무표 전체 조회. 시각 override가 없는 날은 근무유형의 기본 시각을 대신 채워서 내려준다. */
    @Transactional(readOnly = true)
    public List<ScheduleDayView> getSchedule() {
        Long userId = CurrentUser.id();
        Map<ShiftType, ShiftTypeDefault> defaults = shiftTypeDefaultRepository.findByUserProfileId(userId).stream()
                .collect(Collectors.toMap(ShiftTypeDefault::getShiftType, d -> d));

        return shiftRepository.findByUserProfileIdOrderByDateAsc(userId).stream()
                .map(s -> toScheduleDayView(s, defaults))
                .toList();
    }

    private ScheduleDayView toScheduleDayView(Shift s, Map<ShiftType, ShiftTypeDefault> defaults) {
        if (s.getShiftType() == ShiftType.OFF) {
            return new ScheduleDayView(s.getDate(), ShiftType.OFF, null, null, false);
        }
        boolean hasCustomTime = s.getStartTimeOverride() != null || s.getEndTimeOverride() != null;
        LocalTime start = s.getStartTimeOverride();
        LocalTime end = s.getEndTimeOverride();
        if (start == null || end == null) {
            ShiftTypeDefault def = defaults.get(s.getShiftType());
            if (start == null && def != null) {
                start = def.getDefaultStartTime();
            }
            if (end == null && def != null) {
                end = def.getDefaultEndTime();
            }
        }
        return new ScheduleDayView(s.getDate(), s.getShiftType(), start, end, hasCustomTime);
    }

    /**
     * 등록된 근무표 중 하루만 근무유형/시각을 수정한다. 대상 날짜와 그 전후날(모드 판정이 인접일에
     * 의존하므로)의 RoutineResult를 규칙 기반으로 재계산해서 새 version으로 반영한다. AI 재호출은
     * 하지 않는다(빠른 수정 용도) — 필요하면 이후 GET /api/routines/today 조회 시 체크인 트리거로
     * AI 개인화가 다시 붙는다.
     */
    @Transactional
    public void editShift(LocalDate date, ShiftType shiftType, LocalTime startTimeOverride, LocalTime endTimeOverride) {
        Long userId = CurrentUser.id();
        userProfileRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("프로필을 먼저 등록해야 합니다"));

        Shift entity = shiftRepository.findByUserProfileIdAndDate(userId, date)
                .orElseThrow(() -> new ShiftNotFoundException(date));
        entity.setShiftType(shiftType);
        entity.setStartTimeOverride(shiftType == ShiftType.OFF ? null : startTimeOverride);
        entity.setEndTimeOverride(shiftType == ShiftType.OFF ? null : endTimeOverride);
        shiftRepository.save(entity);
        shiftRepository.flush();

        List<LocalDate> affected = List.of(date.minusDays(1), date, date.plusDays(1)).stream()
                .filter(d -> shiftRepository.findByUserProfileIdAndDate(userId, d).isPresent())
                .sorted()
                .toList();
        for (LocalDate d : affected) {
            recomputeDay(userId, d);
        }
    }

    private void recomputeDay(Long userId, LocalDate date) {
        RoutineComputation computation = computationService.compute(date);
        SleepBlock sb = computation.sleepBlock();
        MealBlock mb = computation.mealBlock();
        MealTimes mealTimes = new MealTimes(null, null, null,
                mb.bigMealCutoff(), mb.nightRestrictionStart(), mb.nightRestrictionEnd(), mb.caffeineCutoff());

        Optional<RoutineResult> existingCurrent = routineResultRepository.findByUserProfileIdAndDateAndIsCurrentTrue(userId, date);
        int newVersion = routineResultRepository.findFirstByUserProfileIdAndDateOrderByVersionDesc(userId, date)
                .map(r -> r.getVersion() + 1)
                .orElse(1);
        ReplanReason reason = existingCurrent.isPresent() ? ReplanReason.SHIFT_CHANGE : null;

        existingCurrent.ifPresent(prev -> {
            prev.setCurrent(false);
            routineResultRepository.save(prev);
        });

        RoutineResult next = new RoutineResult(userId, date, newVersion, true, reason, computation.mode(),
                sb.mainSleepStart(), sb.mainSleepEnd(), sb.supplementarySleepStart(), sb.supplementarySleepEnd(),
                sb.napMinutes(), mealTimes);
        routineResultRepository.save(next);
        routineResultRepository.flush();
    }

    private SuggestAdjustmentRequest buildSuggestRequest(UserProfile profile, RoutineComputation computation,
                                                           SuggestAdjustmentRequest.History history, String todayContext) {
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
        return new SuggestAdjustmentRequest(computation.mode().name(), windowDto, currentBlockDto, mealConstraintsDto, history, todayContext);
    }

    private SuggestAdjustmentRequest.History emptyHistory(UserProfile profile) {
        return new SuggestAdjustmentRequest.History(List.of(), List.of(), List.of(), List.of(), List.of(),
                profile.getRhythmPreference() == null ? null : profile.getRhythmPreference().name());
    }

    /**
     * AI가 절대시각으로 돌려준 수면/식사를 sleepWindow·anchor·mealConstraints 기준으로 clamp해서 적용한다.
     * AI 응답이 없으면(타임아웃/실패) 규칙 기반 초안을 그대로 유지한다.
     */
    private void applyResponse(RoutineResult r, SuggestAdjustmentResponse response, RoutineComputation computation) {
        MealBlock mb = computation.mealBlock();
        if (response == null) {
            r.setMealTimes(new MealTimes(mb.bigMealCutoff(), null, null, mb.bigMealCutoff(), mb.nightRestrictionStart(), mb.nightRestrictionEnd(), mb.caffeineCutoff()));
            return;
        }

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
        SleepBlock clampedSleep = AiSleepMealValidator.clampSleep(proposedSleep, computation.sleepBlock(), computation.sleepWindow());
        if (AiSleepMealValidator.isMainSleepSuspiciouslyShort(clampedSleep, computation.sleepBlock())) {
            log.warn("AI가 제안한 주수면이 규칙 기반 초안보다 80% 미만으로 짧아 규칙 기반 초안을 대신 사용합니다: date={}", r.getDate());
            clampedSleep = computation.sleepBlock();
        }

        LocalTime mainMeal = LocalTime.parse(response.meal().mainMealTime());
        LocalTime clampedMainMeal = AiSleepMealValidator.clampMainMeal(mainMeal, mb.bigMealCutoff(), mb.nightRestrictionStart(), mb.nightRestrictionEnd());
        LocalTime subMeal = response.meal().subMealTime() == null ? null : LocalTime.parse(response.meal().subMealTime());
        LocalTime snackTime = response.meal().snackNeeded() && response.meal().snackTime() != null
                ? LocalTime.parse(response.meal().snackTime())
                : null;
        // 간식이 주수면 시작 이후로 밀리면 의미가 없으므로 주수면 시작 전으로 clamp
        if (snackTime != null) {
            long toSnack = SleepTimeMath.minutesBetween(mb.nightRestrictionEnd(), snackTime);
            long toSleepStart = SleepTimeMath.minutesBetween(mb.nightRestrictionEnd(), clampedSleep.mainSleepStart().toLocalTime());
            if (toSnack > toSleepStart) {
                snackTime = clampedSleep.mainSleepStart().toLocalTime().minusMinutes(SNACK_BEFORE_SLEEP_MINUTES);
            }
        }

        r.setSleepStart(clampedSleep.mainSleepStart());
        r.setSleepEnd(clampedSleep.mainSleepEnd());
        r.setSupplementarySleepStart(clampedSleep.supplementarySleepStart());
        r.setSupplementarySleepEnd(clampedSleep.supplementarySleepEnd());
        r.setNapMinutes(clampedSleep.napMinutes());
        r.setMealTimes(new MealTimes(clampedMainMeal, subMeal, snackTime, mb.bigMealCutoff(), mb.nightRestrictionStart(), mb.nightRestrictionEnd(), mb.caffeineCutoff()));
        r.setAiReason(response.sleep().reason() + " / " + response.meal().reason());
        r.setAiUpdatedAt(LocalDateTime.now());
    }
}

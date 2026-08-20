package com.shiftrhythm.backend.domain.routine;

import com.shiftrhythm.backend.domain.routine.service.OnboardingService;
import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.RhythmPreference;
import com.shiftrhythm.backend.domain.schedule.ShiftNotFoundException;
import com.shiftrhythm.backend.domain.schedule.ShiftType;
import com.shiftrhythm.backend.domain.schedule.util.AppClock;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import com.shiftrhythm.backend.domain.schedule.repository.UserProfileRepository;
import com.shiftrhythm.backend.web.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 등록된 근무표 조회(getSchedule)와 특정 날짜 수정(editShift) 검증.
 * fakeai 프로필로 구동해 온보딩 등록(registerSchedule) 단계의 AI 호출이 네트워크 없이도 동작하게 한다.
 */
@SpringBootTest
@ActiveProfiles("fakeai")
@Transactional
class OnboardingServiceScheduleEditTest {

    // registerSchedule이 이제 "오늘이 범위 안에 있어야" 저장을 허용하므로 D0는 오늘이어야 한다.
    // AppClock(KST 고정)과 동일한 기준을 써야 한다 — LocalDate.now()(시스템 기본 타임존)를 쓰면 CI
    // 러너처럼 기본 타임존이 UTC인 환경에서 KST 자정 전후(UTC 15~24시) 실행 시 하루가 어긋나 flaky해진다.
    private static final LocalDate D0 = AppClock.now().toLocalDate();

    @Autowired
    private OnboardingService onboardingService;
    @Autowired
    private RoutineResultRepository routineResultRepository;
    @Autowired
    private UserProfileRepository userProfileRepository;

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @BeforeEach
    void setUp() {
        // 헤더 없이 등록하면 새 사용자가 발급된다 — 그 id를 현재 사용자로 세팅해야 이후 호출이 같은 사용자를 본다.
        CurrentUser.set(onboardingService
                .upsertProfile("테스터", 30, 30, 420, false, null, RhythmPreference.BALANCED).getId());
        onboardingService.registerSchedule(
                List.of(
                        new OnboardingService.ShiftTypeDefaultInput(ShiftType.DAY, LocalTime.of(9, 0), LocalTime.of(18, 0)),
                        new OnboardingService.ShiftTypeDefaultInput(ShiftType.EVENING, LocalTime.of(14, 0), LocalTime.of(22, 0)),
                        new OnboardingService.ShiftTypeDefaultInput(ShiftType.NIGHT, LocalTime.of(22, 0), LocalTime.of(7, 0))
                ),
                List.of(
                        new OnboardingService.ShiftInput(D0, ShiftType.DAY),
                        new OnboardingService.ShiftInput(D0.plusDays(1), ShiftType.DAY),
                        new OnboardingService.ShiftInput(D0.plusDays(2), ShiftType.DAY)
                ));
    }

    @Test
    void getSchedule_fillsDefaultTimeWhenNoOverride() {
        List<ScheduleDayView> views = onboardingService.getSchedule();

        ScheduleDayView day = views.stream().filter(v -> v.date().equals(D0)).findFirst().orElseThrow();
        assertThat(day.shiftType()).isEqualTo(ShiftType.DAY);
        assertThat(day.startTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(day.endTime()).isEqualTo(LocalTime.of(18, 0));
        assertThat(day.hasCustomTime()).isFalse();
    }

    @Test
    void editShift_unregisteredDate_throwsNotFound() {
        assertThatThrownBy(() -> onboardingService.editShift(D0.plusDays(30), ShiftType.DAY, null, null))
                .isInstanceOf(ShiftNotFoundException.class);
    }

    @Test
    void editShift_changesTypeAndCustomTime_reflectedInSchedule() {
        // NIGHT는 다음날(D0+2)이 DAY라 "역방향 급전환"으로 막히므로(InvalidShiftTransitionException),
        // 양방향 다 허용되는 EVENING으로 검증한다.
        onboardingService.editShift(D0.plusDays(1), ShiftType.EVENING, LocalTime.of(15, 0), LocalTime.of(23, 0));

        ScheduleDayView day = onboardingService.getSchedule().stream()
                .filter(v -> v.date().equals(D0.plusDays(1))).findFirst().orElseThrow();
        assertThat(day.shiftType()).isEqualTo(ShiftType.EVENING);
        assertThat(day.startTime()).isEqualTo(LocalTime.of(15, 0));
        assertThat(day.endTime()).isEqualTo(LocalTime.of(23, 0));
        assertThat(day.hasCustomTime()).isTrue();
    }

    @Test
    void editShift_toOff_clearsCustomTime() {
        onboardingService.editShift(D0.plusDays(1), ShiftType.OFF, LocalTime.of(23, 0), LocalTime.of(8, 0));

        ScheduleDayView day = onboardingService.getSchedule().stream()
                .filter(v -> v.date().equals(D0.plusDays(1))).findFirst().orElseThrow();
        assertThat(day.shiftType()).isEqualTo(ShiftType.OFF);
        assertThat(day.startTime()).isNull();
        assertThat(day.endTime()).isNull();
    }

    @Test
    void editShift_recomputesEditedDayAndNeighborsAsNewVersion() {
        // D0/D0+1/D0+2 전부 등록 시 version=1이 생성돼 있다. 가운데 날을 EVENING으로 바꾸면
        // (NIGHT는 다음날 DAY와 조합 시 InvalidShiftTransitionException이라 EVENING으로 검증)
        // 셋 다(이웃날 판정에 영향) 새 버전이 생기고, 이전 version=1은 is_current=false가 돼야 한다.
        onboardingService.editShift(D0.plusDays(1), ShiftType.EVENING, null, null);

        for (LocalDate date : List.of(D0, D0.plusDays(1), D0.plusDays(2))) {
            RoutineResult current = routineResultRepository
                    .findByUserProfileIdAndDateAndIsCurrentTrue(CurrentUser.id(), date).orElseThrow();
            assertThat(current.getVersion()).isEqualTo(2);
            assertThat(current.getReplanReason()).isEqualTo(ReplanReason.SHIFT_CHANGE);

            RoutineResult v1 = routineResultRepository
                    .findByUserProfileIdAndDateAndVersion(CurrentUser.id(), date, 1).orElseThrow();
            assertThat(v1.isCurrent()).isFalse();
        }

        // D0+1은 EVENING이지만 다음날(D0+2)이 DAY라 안정 모드가 아니라 SHIFT_TRANSITION으로 판정된다.
        RoutineResult editedDay = routineResultRepository
                .findByUserProfileIdAndDateAndIsCurrentTrue(CurrentUser.id(), D0.plusDays(1)).orElseThrow();
        assertThat(editedDay.getMode().name()).isEqualTo("SHIFT_TRANSITION");
    }

    @Test
    void registerSchedule_calledAgainWithAlreadyRegisteredDate_upsertsInsteadOfThrowing() {
        // D0는 setUp에서 이미 등록됨(version=1). 같은 날짜를 포함해서 registerSchedule을 다시 호출하면
        // 유니크 제약(user_profile_id, date, version) 위반 500 대신 새 version으로 upsert돼야 한다.
        onboardingService.registerSchedule(
                List.of(new OnboardingService.ShiftTypeDefaultInput(ShiftType.DAY, LocalTime.of(9, 0), LocalTime.of(18, 0))),
                List.of(new OnboardingService.ShiftInput(D0, ShiftType.DAY))
        );

        RoutineResult current = routineResultRepository
                .findByUserProfileIdAndDateAndIsCurrentTrue(CurrentUser.id(), D0).orElseThrow();
        assertThat(current.getVersion()).isEqualTo(2);
        assertThat(current.getReplanReason()).isEqualTo(ReplanReason.SHIFT_CHANGE);

        RoutineResult v1 = routineResultRepository
                .findByUserProfileIdAndDateAndVersion(CurrentUser.id(), D0, 1).orElseThrow();
        assertThat(v1.isCurrent()).isFalse();
    }

    @Test
    void registerSchedule_withoutToday_isRejected() {
        // 오늘이 빠진 근무표는 저장 자체가 막혀야 한다 — PATCH /{date}는 기존 날짜 수정 전용이라
        // 나중에 오늘을 추가할 방법이 없으므로, 등록 시점에 막지 않으면 /routines/today가 뒤늦게 터진다.
        assertThatThrownBy(() -> onboardingService.registerSchedule(
                List.of(new OnboardingService.ShiftTypeDefaultInput(ShiftType.DAY, LocalTime.of(9, 0), LocalTime.of(18, 0))),
                List.of(new OnboardingService.ShiftInput(D0.plusDays(5), ShiftType.DAY))
        )).isInstanceOf(ScheduleMissingTodayException.class);

        // 거부됐으니 D0(setUp에서 등록된 오늘 근무표)는 그대로 남아있어야 한다.
        RoutineResult stillThere = routineResultRepository
                .findByUserProfileIdAndDateAndIsCurrentTrue(CurrentUser.id(), D0).orElseThrow();
        assertThat(stillThere.getVersion()).isEqualTo(1);
    }

    @Test
    void anchorShiftsToStartDate_thenReRegister_movesAllDatesAndKeepsRelativePattern() {
        // setUp에서 D0/D0+1/D0+2(전부 DAY)가 등록돼있다. 홈 화면에서 이미 등록된 근무표를 고치는 흐름을
        // 그대로 재현: GET으로 기존 값을 읽어와 anchorShiftsToStartDate로 재배치한 뒤 registerSchedule로
        // 재등록한다. 시작일을 하루 앞당기면 요일 패턴(DAY 3연속)은 그대로 유지한 채 절대 날짜만
        // D0-1/D0/D0+1로 밀려야 하고, 오늘(D0)은 둘째 날로 여전히 포함된다.
        LocalDate newStart = D0.minusDays(1);

        List<OnboardingService.ShiftInput> existing = onboardingService.getSchedule().stream()
                .map(v -> new OnboardingService.ShiftInput(v.date(), v.shiftType()))
                .toList();
        List<OnboardingService.ShiftInput> anchored = OnboardingService.anchorShiftsToStartDate(existing, newStart);
        onboardingService.registerSchedule(
                List.of(new OnboardingService.ShiftTypeDefaultInput(ShiftType.DAY, LocalTime.of(9, 0), LocalTime.of(18, 0))),
                anchored
        );

        List<ScheduleDayView> views = onboardingService.getSchedule();
        assertThat(views).extracting(ScheduleDayView::date)
                .containsExactly(newStart, newStart.plusDays(1), newStart.plusDays(2));
        assertThat(views).extracting(ScheduleDayView::shiftType)
                .containsExactly(ShiftType.DAY, ShiftType.DAY, ShiftType.DAY);

        RoutineResult todayRoutine = routineResultRepository
                .findByUserProfileIdAndDateAndIsCurrentTrue(CurrentUser.id(), D0).orElseThrow();
        assertThat(todayRoutine.getMode().name()).isEqualTo("DAY");
    }

    @Test
    void anchorShiftsToStartDate_toRangeMissingToday_isRejectedOnReRegister() {
        // 시작일을 수정해도 결과 범위에 오늘이 안 들어가면 registerSchedule이 그대로 거부해야 한다.
        List<OnboardingService.ShiftInput> existing = onboardingService.getSchedule().stream()
                .map(v -> new OnboardingService.ShiftInput(v.date(), v.shiftType()))
                .toList();
        List<OnboardingService.ShiftInput> anchored = OnboardingService.anchorShiftsToStartDate(existing, D0.plusDays(5));

        assertThatThrownBy(() -> onboardingService.registerSchedule(
                List.of(new OnboardingService.ShiftTypeDefaultInput(ShiftType.DAY, LocalTime.of(9, 0), LocalTime.of(18, 0))),
                anchored
        )).isInstanceOf(ScheduleMissingTodayException.class);
    }
}

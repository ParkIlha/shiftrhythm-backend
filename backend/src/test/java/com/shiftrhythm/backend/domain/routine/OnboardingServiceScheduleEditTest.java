package com.shiftrhythm.backend.domain.routine;

import com.shiftrhythm.backend.domain.routine.service.OnboardingService;
import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.RhythmPreference;
import com.shiftrhythm.backend.domain.schedule.ShiftNotFoundException;
import com.shiftrhythm.backend.domain.schedule.ShiftType;
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

    private static final LocalDate D0 = LocalDate.now().plusDays(10); // 과거 데이터와 안 겹치게 미래로

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
}

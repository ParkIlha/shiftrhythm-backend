package com.shiftrhythm.backend.domain.routine;

import com.shiftrhythm.backend.domain.ai.AiScheduleAdapter;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentResponse;
import com.shiftrhythm.backend.domain.checkin.entity.DailyCheckIn;
import com.shiftrhythm.backend.domain.checkin.repository.DailyCheckInRepository;
import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.RhythmPreference;
import com.shiftrhythm.backend.domain.schedule.RoutineMode;
import com.shiftrhythm.backend.domain.schedule.ShiftType;
import com.shiftrhythm.backend.domain.schedule.entity.Shift;
import com.shiftrhythm.backend.domain.schedule.entity.ShiftTypeDefault;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import com.shiftrhythm.backend.domain.schedule.repository.ShiftRepository;
import com.shiftrhythm.backend.domain.schedule.repository.ShiftTypeDefaultRepository;
import com.shiftrhythm.backend.domain.schedule.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AI 서버가 형식이 깨진 시각을 반환해도 GET /api/routines/today가 500 없이 기존 루틴을
 * 그대로 유지하는지, 정상 응답일 때는 실제로 반영되는지 검증한다.
 *
 * 클래스에 @Transactional을 붙여 각 테스트 메서드가 끝나면 자동 롤백되게 한다 — @BeforeEach가
 * 매번 같은 날짜에 RoutineResult를 새로 insert하므로, 롤백 없이는 두 번째 테스트부터
 * isCurrent=true 행이 중복되어 NonUniqueResultException이 발생한다.
 */
@SpringBootTest
@Transactional
class RoutineFacadeAiFallbackTest {

    private static final LocalDate DATE = LocalDate.now();

    @Autowired
    private RoutineFacade routineFacade;
    @Autowired
    private UserProfileRepository userProfileRepository;
    @Autowired
    private ShiftTypeDefaultRepository shiftTypeDefaultRepository;
    @Autowired
    private ShiftRepository shiftRepository;
    @Autowired
    private RoutineResultRepository routineResultRepository;
    @Autowired
    private DailyCheckInRepository dailyCheckInRepository;

    @MockBean
    private AiScheduleAdapter aiScheduleAdapter;

    private RoutineResult original;

    @BeforeEach
    void setUp() {
        userProfileRepository.save(new UserProfile("테스터", 30, 30, 420, false, null, RhythmPreference.BALANCED));
        shiftTypeDefaultRepository.save(new ShiftTypeDefault(UserProfile.SINGLETON_ID, ShiftType.DAY, LocalTime.of(9, 0), LocalTime.of(18, 0)));
        shiftRepository.save(new Shift(UserProfile.SINGLETON_ID, DATE, ShiftType.DAY, null, null));
        shiftRepository.save(new Shift(UserProfile.SINGLETON_ID, DATE.plusDays(1), ShiftType.DAY, null, null));

        MealTimes mealTimes = new MealTimes(LocalTime.of(7, 0), null, null,
                LocalTime.of(21, 0), LocalTime.of(0, 0), LocalTime.of(6, 0), LocalTime.of(18, 0));
        original = new RoutineResult(UserProfile.SINGLETON_ID, DATE, 1, true, null, RoutineMode.DAY,
                LocalTime.of(23, 0), LocalTime.of(6, 30), null, null, null, mealTimes);
        routineResultRepository.save(original);

        DailyCheckIn checkIn = new DailyCheckIn(UserProfile.SINGLETON_ID, DATE);
        checkIn.setConditionScore(4);
        dailyCheckInRepository.save(checkIn);
    }

    @Test
    void malformedAiResponse_keepsExistingRoutineInstead_of500() {
        SuggestAdjustmentResponse malformed = new SuggestAdjustmentResponse(
                new SuggestAdjustmentResponse.Sleep("9:00", "6:30", null, null, null, "reason"), // "9:00"은 LocalTime.parse가 거부하는 형식
                new SuggestAdjustmentResponse.Meal("7:00", null, false, null, "reason")
        );
        when(aiScheduleAdapter.suggestAdjustment(any())).thenReturn(Optional.of(malformed));

        TodayRoutineView view = routineFacade.getToday(DATE);

        assertThat(view.wasJustPersonalized()).isFalse();
        RoutineResult stillCurrent = routineResultRepository
                .findByUserProfileIdAndDateAndIsCurrentTrue(UserProfile.SINGLETON_ID, DATE).orElseThrow();
        assertThat(stillCurrent.getSleepStart()).isEqualTo(LocalTime.of(23, 0));
        assertThat(stillCurrent.getSleepEnd()).isEqualTo(LocalTime.of(6, 30));
    }

    @Test
    void wellFormedAiResponse_isAppliedAndMarkedPersonalized() {
        SuggestAdjustmentResponse ok = new SuggestAdjustmentResponse(
                new SuggestAdjustmentResponse.Sleep("23:30", "07:00", null, null, null, "수면 사유"),
                new SuggestAdjustmentResponse.Meal("07:30", null, false, null, "식사 사유")
        );
        when(aiScheduleAdapter.suggestAdjustment(any())).thenReturn(Optional.of(ok));

        TodayRoutineView view = routineFacade.getToday(DATE);

        assertThat(view.wasJustPersonalized()).isTrue();
        RoutineResult updated = routineResultRepository
                .findByUserProfileIdAndDateAndIsCurrentTrue(UserProfile.SINGLETON_ID, DATE).orElseThrow();
        assertThat(updated.getAiUpdatedAt()).isNotNull();
    }
}

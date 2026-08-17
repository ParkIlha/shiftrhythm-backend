package com.shiftrhythm.backend.domain.routine;

import com.shiftrhythm.backend.domain.ai.AiScheduleAdapter;
import com.shiftrhythm.backend.domain.ai.dto.ParseDisruptionResponse;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentResponse;
import com.shiftrhythm.backend.domain.schedule.RhythmPreference;
import com.shiftrhythm.backend.domain.schedule.ShiftType;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * AI 서버가 백엔드 ReplanReason enum에 없는 reasonCategory(예: 과거 계약이던 "DINNER_GATHERING")를
 * 반환해도 confirm이 500으로 죽지 않고 OTHER로 안전하게 떨어지는지 검증한다. 실제 AI 서버 계약은
 * prompts.py에서도 고쳤지만, 미래의 계약 드리프트에도 안 죽는지는 여기서 별도로 보장한다.
 */
@SpringBootTest
@Transactional
class ReplanFacadeUnknownReasonTest {

    private static final LocalDate DATE = LocalDate.now();

    @Autowired
    private OnboardingService onboardingService;
    @Autowired
    private ReplanFacade replanFacade;

    @MockBean
    private AiScheduleAdapter aiScheduleAdapter;

    @BeforeEach
    void setUp() {
        onboardingService.upsertProfile("tester", 30, 30, 420, false, null, RhythmPreference.BALANCED);

        // registerSchedule 내부에서도 suggestAdjustment를 호출하므로 폴백(빈 응답)을 지정해둔다.
        when(aiScheduleAdapter.suggestAdjustment(any())).thenReturn(Optional.empty());
        onboardingService.registerSchedule(
                List.of(new OnboardingService.ShiftTypeDefaultInput(ShiftType.DAY, LocalTime.of(9, 0), LocalTime.of(18, 0))),
                List.of(
                        new OnboardingService.ShiftInput(DATE, ShiftType.DAY),
                        new OnboardingService.ShiftInput(DATE.plusDays(1), ShiftType.DAY)
                ));
    }

    @Test
    void confirm_withUnknownReasonCategory_fallsBackToOtherInsteadOf500() {
        when(aiScheduleAdapter.parseDisruption(any())).thenReturn(Optional.of(
                new ParseDisruptionResponse("SHIFT_END_DELAY", 120, "2026-01-01T00:00:00", "DINNER_GATHERING")
        ));
        when(aiScheduleAdapter.suggestAdjustment(any())).thenReturn(Optional.of(new SuggestAdjustmentResponse(
                new SuggestAdjustmentResponse.Sleep("23:00", "07:00", null, null, null, "reason"),
                new SuggestAdjustmentResponse.Meal("07:30", null, false, null, "reason")
        )));

        ReplanFacade.PreviewResult preview = replanFacade.preview("오늘 회식이 있어요");

        assertThatCode(() -> {
            ReplanFacade.ConfirmResult result = replanFacade.confirm(preview.previewId());
            assertThat(result.ok()).isTrue();
        }).doesNotThrowAnyException();
    }
}

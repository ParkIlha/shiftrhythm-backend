package com.shiftrhythm.backend.domain.routine.service;

import com.shiftrhythm.backend.domain.schedule.ShiftType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OnboardingService.anchorShiftsToStartDate — DB 접근 없는 순수 계산이라 SpringBootTest 없이
 * 가볍게 검증한다. 저장 전(온보딩 파싱 검토 화면) 단계의 시작일 보정용.
 */
class OnboardingServiceAnchorShiftsTest {

    @Test
    void shiftsRelativePatternPreserved_whenAnchoredToNewStartDate() {
        LocalDate oldStart = LocalDate.of(2026, 3, 1); // AI가 잘못 읽은 연/월
        List<OnboardingService.ShiftInput> parsed = List.of(
                new OnboardingService.ShiftInput(oldStart, ShiftType.DAY),
                new OnboardingService.ShiftInput(oldStart.plusDays(1), ShiftType.DAY),
                new OnboardingService.ShiftInput(oldStart.plusDays(2), ShiftType.OFF)
        );

        LocalDate newStart = LocalDate.of(2026, 8, 21); // 사용자가 고른 실제 시작일(오늘)
        List<OnboardingService.ShiftInput> anchored = OnboardingService.anchorShiftsToStartDate(parsed, newStart);

        assertThat(anchored).extracting(OnboardingService.ShiftInput::date)
                .containsExactly(newStart, newStart.plusDays(1), newStart.plusDays(2));
        assertThat(anchored).extracting(OnboardingService.ShiftInput::shiftType)
                .containsExactly(ShiftType.DAY, ShiftType.DAY, ShiftType.OFF);
    }

    @Test
    void unsortedInput_anchorsFromTheEarliestDate() {
        // /schedule/parse 응답이 항상 정렬돼 온다는 보장이 없다고 가정 — 최솟값 기준으로 앵커해야 한다.
        LocalDate d0 = LocalDate.of(2024, 9, 5);
        List<OnboardingService.ShiftInput> parsed = List.of(
                new OnboardingService.ShiftInput(d0.plusDays(2), ShiftType.NIGHT),
                new OnboardingService.ShiftInput(d0, ShiftType.DAY),
                new OnboardingService.ShiftInput(d0.plusDays(1), ShiftType.DAY)
        );

        LocalDate newStart = LocalDate.of(2026, 8, 21);
        List<OnboardingService.ShiftInput> anchored = OnboardingService.anchorShiftsToStartDate(parsed, newStart);

        assertThat(anchored).extracting(OnboardingService.ShiftInput::date)
                .containsExactly(newStart.plusDays(2), newStart, newStart.plusDays(1));
    }

    @Test
    void emptyInput_returnsEmpty() {
        assertThat(OnboardingService.anchorShiftsToStartDate(List.of(), LocalDate.of(2026, 8, 21))).isEmpty();
    }
}

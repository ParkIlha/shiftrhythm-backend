package com.shiftrhythm.backend.domain.ai;

import com.shiftrhythm.backend.domain.ai.dto.ParseDisruptionRequest;
import com.shiftrhythm.backend.domain.ai.dto.ParseDisruptionResponse;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleRequest;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleResponse;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentRequest;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ai-server 없이 로컬 개발/검증할 때 쓰는 고정값 어댑터. `fakeai` 프로필에서만 활성화된다.
 */
@Component
@Profile("fakeai")
public class FakeAiScheduleAdapter implements AiScheduleAdapter {

    @Override
    public Optional<ParseScheduleResponse> parseSchedule(ParseScheduleRequest request) {
        return Optional.of(new ParseScheduleResponse(
                List.of(new ParseScheduleResponse.ShiftTypeDef("DAY", "07:00", "15:00")),
                List.of(new ParseScheduleResponse.ShiftDay("2026-08-17", "DAY"))
        ));
    }

    @Override
    public Optional<ParseDisruptionResponse> parseDisruption(ParseDisruptionRequest request) {
        return Optional.of(new ParseDisruptionResponse(
                "OTHER", 0, LocalDateTime.now().toString(), "OTHER"
        ));
    }

    @Override
    public Optional<SuggestAdjustmentResponse> suggestAdjustment(SuggestAdjustmentRequest request) {
        var current = request.currentSleepBlock();
        return Optional.of(new SuggestAdjustmentResponse(
                new SuggestAdjustmentResponse.Sleep(
                        current.mainSleepStart(), current.mainSleepEnd(),
                        current.supplementarySleepStart(), current.supplementarySleepEnd(),
                        current.napMinutes(), "고정값 응답(fakeai 프로필) — 규칙 기반 초안 그대로 반환"
                ),
                new SuggestAdjustmentResponse.Meal(
                        request.mealConstraints().bigMealCutoff(), null, false, null,
                        "고정값 응답(fakeai 프로필) — bigMealCutoff를 그대로 주요식사 시각으로 사용"
                )
        ));
    }
}

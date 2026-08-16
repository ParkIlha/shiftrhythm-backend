package com.shiftrhythm.backend.domain.ai;

import com.shiftrhythm.backend.domain.ai.dto.ParseDisruptionRequest;
import com.shiftrhythm.backend.domain.ai.dto.ParseDisruptionResponse;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleRequest;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleResponse;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentRequest;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentResponse;

import java.util.Optional;

/**
 * ai-server(FastAPI) 호출 추상화. 실패(타임아웃/422/5xx)는 예외를 던지지 않고 Optional.empty()로
 * 알려준다 — 호출부가 규칙 기본값으로 폴백할 수 있게.
 */
public interface AiScheduleAdapter {
    Optional<ParseScheduleResponse> parseSchedule(ParseScheduleRequest request);

    Optional<ParseDisruptionResponse> parseDisruption(ParseDisruptionRequest request);

    Optional<SuggestAdjustmentResponse> suggestAdjustment(SuggestAdjustmentRequest request);
}

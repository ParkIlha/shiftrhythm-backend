package com.shiftrhythm.backend.domain.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiftrhythm.backend.domain.ai.dto.ParseDisruptionRequest;
import com.shiftrhythm.backend.domain.ai.dto.ParseDisruptionResponse;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleRequest;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleResponse;
import com.shiftrhythm.backend.domain.ai.dto.RowLabelRequiredBody;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentRequest;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentResponse;
import com.shiftrhythm.backend.domain.routine.RowLabelRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.function.Supplier;

@Component
@Profile("!fakeai")
public class AiScheduleAdapterImpl implements AiScheduleAdapter {

    private static final Logger log = LoggerFactory.getLogger(AiScheduleAdapterImpl.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AiScheduleAdapterImpl(RestClient aiServerRestClient, ObjectMapper objectMapper) {
        this.restClient = aiServerRestClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ParseScheduleResponse> parseSchedule(ParseScheduleRequest request) {
        return callWithRetry("parse-schedule", () -> restClient.post()
                .uri("/parse-schedule")
                .body(request)
                .retrieve()
                .body(ParseScheduleResponse.class));
    }

    @Override
    public Optional<ParseDisruptionResponse> parseDisruption(ParseDisruptionRequest request) {
        return callWithRetry("parse-disruption", () -> restClient.post()
                .uri("/parse-disruption")
                .body(request)
                .retrieve()
                .body(ParseDisruptionResponse.class));
    }

    @Override
    public Optional<SuggestAdjustmentResponse> suggestAdjustment(SuggestAdjustmentRequest request) {
        return callWithRetry("suggest-adjustment", () -> restClient.post()
                .uri("/suggest-adjustment")
                .body(request)
                .retrieve()
                .body(SuggestAdjustmentResponse.class));
    }

    private <T> Optional<T> callWithRetry(String endpoint, Supplier<T> call) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return Optional.ofNullable(call.get());
            } catch (HttpClientErrorException.UnprocessableEntity e) {
                RowLabelRequiredBody body = tryParseRowLabelRequired(e);
                if (body != null) {
                    // 근무표에 행이 여러 개라 AI가 확정 못 지은 상태 — 재시도해도 같은 응답이 돌아오므로 즉시 상위로 전달
                    throw new RowLabelRequiredException(body.rowLabels());
                }
                log.warn("AI 서버 호출 실패 ({}), attempt={}: {}", endpoint, attempt, e.getMessage());
            } catch (Exception e) {
                log.warn("AI 서버 호출 실패 ({}), attempt={}: {}", endpoint, attempt, e.getMessage());
            }
        }
        return Optional.empty();
    }

    private RowLabelRequiredBody tryParseRowLabelRequired(HttpClientErrorException.UnprocessableEntity e) {
        try {
            RowLabelRequiredBody body = objectMapper.readValue(e.getResponseBodyAsByteArray(), RowLabelRequiredBody.class);
            return "ROW_LABEL_REQUIRED".equals(body.error()) ? body : null;
        } catch (Exception parseError) {
            return null;
        }
    }
}

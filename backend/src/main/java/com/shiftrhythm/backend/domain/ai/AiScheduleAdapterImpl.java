package com.shiftrhythm.backend.domain.ai;

import com.shiftrhythm.backend.domain.ai.dto.ParseDisruptionRequest;
import com.shiftrhythm.backend.domain.ai.dto.ParseDisruptionResponse;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleRequest;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleResponse;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentRequest;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.function.Supplier;

@Component
@Profile("!fakeai")
public class AiScheduleAdapterImpl implements AiScheduleAdapter {

    private static final Logger log = LoggerFactory.getLogger(AiScheduleAdapterImpl.class);

    private final RestClient restClient;

    public AiScheduleAdapterImpl(RestClient aiServerRestClient) {
        this.restClient = aiServerRestClient;
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
            } catch (Exception e) {
                log.warn("AI 서버 호출 실패 ({}), attempt={}: {}", endpoint, attempt, e.getMessage());
            }
        }
        return Optional.empty();
    }
}

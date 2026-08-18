package com.shiftrhythm.backend.domain.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiftrhythm.backend.domain.ai.dto.ParseDisruptionRequest;
import com.shiftrhythm.backend.domain.ai.dto.ParseDisruptionResponse;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleRequest;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleResponse;
import com.shiftrhythm.backend.domain.ai.dto.RowLabelRequiredBody;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentRequest;
import com.shiftrhythm.backend.domain.ai.dto.SuggestAdjustmentResponse;
import com.shiftrhythm.backend.domain.routine.ParseFailedException;
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
                    throw new RowLabelRequiredException(body.rowLabels(), body.rowPreviews());
                }
                // AI가 "계획을 바꿀 일은 아니다"라고 판단하고 대신 써 보낸 답변 — 그대로 사용자에게 보여준다.
                // parse-disruption 에만 적용한다. 다른 엔드포인트의 422 는 폴백으로 흘러가야 해서(홈 화면이
                // 죽으면 안 된다) 여기서 예외로 바꾸지 않는다.
                if ("parse-disruption".equals(endpoint)) {
                    String message = tryParseMessage(e);
                    if (message != null) {
                        throw new ParseFailedException(message);
                    }
                }
                // 422 는 AI 서버가 내린 '판정'이지 일시적 장애가 아니다(사진을 못 읽음 등).
                // 재시도해도 같은 답이 오는데 vision 호출 비용(사진 한 장 ≈ 3000토큰)만 두 번 나가고
                // 사용자 대기시간도 두 배가 된다. 여기서 끊는다.
                log.warn("AI 서버가 처리 불가 응답 ({}): {}", endpoint, e.getMessage());
                return Optional.empty();
            } catch (Exception e) {
                log.warn("AI 서버 호출 실패 ({}), attempt={}: {}", endpoint, attempt, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** 422 본문에 message 가 있으면 꺼낸다. IMAGE_UNREADABLE 처럼 없는 응답은 null. */
    private String tryParseMessage(HttpClientErrorException.UnprocessableEntity e) {
        try {
            JsonNode message = objectMapper.readTree(e.getResponseBodyAsByteArray()).path("message");
            return message.isTextual() ? message.asText() : null;
        } catch (Exception parseError) {
            return null;
        }
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

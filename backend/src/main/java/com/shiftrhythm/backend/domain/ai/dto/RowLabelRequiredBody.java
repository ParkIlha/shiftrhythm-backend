package com.shiftrhythm.backend.domain.ai.dto;

import java.util.List;

/**
 * AI 서버가 parse-schedule 422 ROW_LABEL_REQUIRED와 함께 반환하는 응답 바디.
 * rowPreviews는 rowLabels와 같은 순서·길이의 행별 근무 패턴 요약(예: "N N OFF OFF N N OFF")이다.
 * ai-server가 rowLabels/rowPreviews 길이가 안 맞으면 rowPreviews 자체를 응답에서 빼므로, 이 경우
 * Jackson이 null로 채운다 — 필드가 없어도 역직렬화는 안전하다.
 */
public record RowLabelRequiredBody(String error, List<String> rowLabels, List<String> rowPreviews) {
}

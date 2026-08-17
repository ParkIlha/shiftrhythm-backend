package com.shiftrhythm.backend.domain.ai.dto;

import java.util.List;

/** AI 서버가 parse-schedule 422 ROW_LABEL_REQUIRED와 함께 반환하는 응답 바디. */
public record RowLabelRequiredBody(String error, List<String> rowLabels) {
}

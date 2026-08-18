package com.shiftrhythm.backend.web;

import com.shiftrhythm.backend.domain.routine.RowLabelRequiredException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    @Test
    void handleRowLabelRequired_returns422WithRowLabelsAndRowPreviews() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        RowLabelRequiredException e = new RowLabelRequiredException(
                List.of("김OO", "이OO", "박OO"),
                List.of("N N OFF OFF N N OFF", "D D D OFF OFF D D", "OFF D D D OFF OFF D"));

        ResponseEntity<Map<String, Object>> response = handler.handleRowLabelRequired(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("error", "ROW_LABEL_REQUIRED");
        assertThat(response.getBody()).containsEntry("rowLabels", List.of("김OO", "이OO", "박OO"));
        assertThat(response.getBody()).containsEntry("rowPreviews",
                List.of("N N OFF OFF N N OFF", "D D D OFF OFF D D", "OFF D D D OFF OFF D"));
    }

    @Test
    void handleRowLabelRequired_omitsRowPreviewsWhenNull() {
        ApiExceptionHandler handler = new ApiExceptionHandler();
        RowLabelRequiredException e = new RowLabelRequiredException(List.of("김OO", "이OO", "박OO"), null);

        ResponseEntity<Map<String, Object>> response = handler.handleRowLabelRequired(e);

        assertThat(response.getBody()).containsEntry("error", "ROW_LABEL_REQUIRED");
        assertThat(response.getBody()).containsKey("rowLabels");
        assertThat(response.getBody()).doesNotContainKey("rowPreviews");
    }
}

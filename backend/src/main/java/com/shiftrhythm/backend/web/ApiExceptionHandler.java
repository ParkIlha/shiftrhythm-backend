package com.shiftrhythm.backend.web;

import com.shiftrhythm.backend.domain.routine.ParseFailedException;
import com.shiftrhythm.backend.domain.routine.PreviewExpiredException;
import com.shiftrhythm.backend.domain.routine.RoutineNotFoundException;
import com.shiftrhythm.backend.domain.routine.RowLabelRequiredException;
import com.shiftrhythm.backend.domain.schedule.InvalidShiftTransitionException;
import com.shiftrhythm.backend.domain.schedule.ShiftNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ParseFailedException.class)
    public ResponseEntity<Map<String, String>> handleParseFailed(ParseFailedException e) {
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("error", "PARSE_FAILED", "message", e.getMessage()));
    }

    @ExceptionHandler(InvalidShiftTransitionException.class)
    public ResponseEntity<Map<String, String>> handleInvalidTransition(InvalidShiftTransitionException e) {
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("error", "INVALID_SHIFT_TRANSITION", "message", e.getMessage()));
    }

    @ExceptionHandler(PreviewExpiredException.class)
    public ResponseEntity<Map<String, String>> handlePreviewExpired(PreviewExpiredException e) {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("error", "PREVIEW_EXPIRED", "message", "재설계 미리보기가 만료되었습니다. 다시 시도해주세요"));
    }

    @ExceptionHandler(RowLabelRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleRowLabelRequired(RowLabelRequiredException e) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "ROW_LABEL_REQUIRED");
        body.put("rowLabels", e.rowLabels());
        if (e.rowPreviews() != null) {
            body.put("rowPreviews", e.rowPreviews());
        }
        return ResponseEntity.unprocessableEntity().body(body);
    }

    @ExceptionHandler(RoutineNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleRoutineNotFound(RoutineNotFoundException e) {
        String date = e.date().toString();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "ROUTINE_NOT_FOUND",
                "message", "%s를 포함한 근무표가 없어요. %s를 포함한 근무표를 다시 올리거나 근무표 시작 날짜를 수정해 주세요."
                        .formatted(date, date)
        ));
    }

    @ExceptionHandler(ShiftNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleShiftNotFound(ShiftNotFoundException e) {
        return ResponseEntity.notFound().build();
    }
}

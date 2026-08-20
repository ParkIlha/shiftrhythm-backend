package com.shiftrhythm.backend.web;

import com.shiftrhythm.backend.domain.routine.ParseFailedException;
import com.shiftrhythm.backend.domain.routine.PreviewExpiredException;
import com.shiftrhythm.backend.domain.routine.RoutineNotFoundException;
import com.shiftrhythm.backend.domain.routine.RowLabelRequiredException;
import com.shiftrhythm.backend.domain.routine.ScheduleMissingTodayException;
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
                "message", "%s를 포함한 근무표가 없어요. %s를 포함한 근무표를 다시 등록해 주세요."
                        .formatted(date, date)
        ));
    }

    /**
     * POST /api/onboarding/schedule에 오늘이 빠진 근무표를 등록하려 할 때. PATCH /{date}는 기존 날짜
     * 수정 전용이라 새 날짜를 못 늘리므로, 통째로 재등록하는 것 외엔 방법이 없다는 걸 명확히 안내한다.
     */
    @ExceptionHandler(ScheduleMissingTodayException.class)
    public ResponseEntity<Map<String, String>> handleScheduleMissingToday(ScheduleMissingTodayException e) {
        String today = e.today().toString();
        return ResponseEntity.unprocessableEntity().body(Map.of(
                "error", "SCHEDULE_MISSING_TODAY",
                "message", "오늘(%s)이 포함되지 않은 근무표는 등록할 수 없어요. 오늘을 포함한 근무표를 다시 등록해 주세요."
                        .formatted(today)
        ));
    }

    @ExceptionHandler(ShiftNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleShiftNotFound(ShiftNotFoundException e) {
        return ResponseEntity.notFound().build();
    }
}

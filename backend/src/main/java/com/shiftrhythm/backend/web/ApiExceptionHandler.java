package com.shiftrhythm.backend.web;

import com.shiftrhythm.backend.domain.routine.ParseFailedException;
import com.shiftrhythm.backend.domain.routine.PreviewExpiredException;
import com.shiftrhythm.backend.domain.routine.RoutineNotFoundException;
import com.shiftrhythm.backend.domain.schedule.InvalidShiftTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ParseFailedException.class)
    public ResponseEntity<Map<String, String>> handleParseFailed(ParseFailedException e) {
        return ResponseEntity.unprocessableEntity()
                .body(Map.of("error", "PARSE_FAILED", "message", "근무 관련 내용으로 다시 입력해주세요"));
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

    @ExceptionHandler(RoutineNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleRoutineNotFound(RoutineNotFoundException e) {
        return ResponseEntity.notFound().build();
    }
}

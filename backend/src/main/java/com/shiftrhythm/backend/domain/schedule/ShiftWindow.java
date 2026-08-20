package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalDateTime;

/**
 * 특정 근무일의 유형과 실제 시각(날짜 포함). OFF 날은 startTime/endTime이 null일 수 있다.
 * endTime은 startTime보다 이를 수 있는 근무(자정을 넘기는 근무)의 경우 이미 다음 날짜로 계산돼 있다
 * (RoutineComputationService.toWindow 참고) — 그래서 이 레코드를 쓰는 쪽은 자정 넘김을 신경 쓸 필요가 없다.
 */
public record ShiftWindow(ShiftType type, LocalDateTime startTime, LocalDateTime endTime) {
}

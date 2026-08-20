package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * type: "근무" | "주수면" | "보조수면" | "파워냅" | "주요식사" | "서브식사" | "자유시간"
 * start/end: 절대 시각(날짜 포함). end가 start보다 이르면 안 된다 — 자정을 넘기는 구간도 end 쪽
 * 날짜를 이미 다음날로 갖고 있어야 한다(호출부 책임).
 */
public record TimelineSegment(String type, LocalDateTime start, LocalDateTime end) {

    /** 이 세그먼트가 시작하는 캘린더 날짜. */
    public LocalDate date() {
        return start.toLocalDate();
    }
}

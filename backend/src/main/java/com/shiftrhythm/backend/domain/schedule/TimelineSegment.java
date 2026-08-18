package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * type: "근무" | "주수면" | "보조수면" | "파워냅" | "주요식사" | "서브식사" | "자유시간"
 * date: 이 세그먼트가 속한 캘린더 날짜(시작일 기준). end가 start보다 이르면 자정을 넘겨
 * date+1일에 끝나는 것으로 취급한다(예: date=8/17, start=22:00, end=06:00 → 8/17 22:00~8/18 06:00).
 */
public record TimelineSegment(String type, LocalDate date, LocalTime start, LocalTime end) {
}

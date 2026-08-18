package com.shiftrhythm.backend.domain.jetlag;

/** UTC offset(-11~+12)과 그 시간대의 대표 국가. 실제 데이터 기준은 offset이고, country는 UI 표현용이다. */
public record JetlagZone(int utcOffset, String country) {
}

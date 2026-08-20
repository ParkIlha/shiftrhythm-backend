package com.shiftrhythm.backend.domain.schedule;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * AI가 절대시각으로 돌려준 수면/식사 제안을 하드 제약(sleepWindow, anchor, mealConstraints)
 * 기준으로 검증하고 벗어난 값을 clamp한다.
 *
 * 수면 쪽은 SleepWindow/SleepBlock이 날짜를 포함한 LocalDateTime이라 순서 비교(isBefore/isAfter)만으로
 * 충분하다. 식사 쪽(clampMainMeal/isWithin)은 날짜 없는 하루 반복 벽시계 기준이라 여전히
 * SleepTimeMath의 순환(LocalTime) 비교를 쓴다.
 */
public final class AiSleepMealValidator {

    private AiSleepMealValidator() {
    }

    /**
     * proposed(AI 제안)를 window로 clamp하되, ruleBased(규칙 기반 초안) 기준 ±adjustToleranceMinutes
     * 범위로도 추가 clamp한다. window만으로 clamp하면 NIGHT처럼 window 자체가 넓은 모드(13시간 등)에서
     * AI가 초안과 무관하게 window 안 어디로든 재배치할 수 있어, "AI는 초안을 미세 조정한다"는 설계
     * 의도가 지켜지지 않기 때문이다. 단, 앵커구간은 하드 제약이라 tolerance보다 우선해 포함시킨다.
     */
    public static SleepBlock clampSleep(SleepBlock proposed, SleepBlock ruleBased, SleepWindow window) {
        LocalDateTime earliest = window.earliestSleepStart();
        LocalDateTime latest = window.latestSleepEnd();
        int tolerance = proposed.adjustToleranceMinutes();

        LocalDateTime start = clampToToleranceWindow(
                clamp(proposed.mainSleepStart(), earliest, latest), ruleBased.mainSleepStart(), tolerance, earliest, latest);
        LocalDateTime end = clampToToleranceWindow(
                clamp(proposed.mainSleepEnd(), earliest, latest), ruleBased.mainSleepEnd(), tolerance, earliest, latest);
        if (end.isBefore(start)) {
            end = start;
        }

        LocalDateTime ankerStart = proposed.ankerBlockStart();
        LocalDateTime ankerEnd = proposed.ankerBlockEnd();
        if (ankerStart != null && ankerEnd != null) {
            // 앵커구간이 수면 밖으로 벗어나면 수면을 앵커를 포함하도록 확장(단, window 밖으로는 못 나감)
            LocalDateTime clampedAnkerStart = clamp(ankerStart, earliest, latest);
            LocalDateTime clampedAnkerEnd = clamp(ankerEnd, earliest, latest);
            if (clampedAnkerStart.isBefore(start)) {
                start = clampedAnkerStart;
            }
            if (clampedAnkerEnd.isAfter(end)) {
                end = clampedAnkerEnd;
            }
        }

        LocalDateTime suppStart = proposed.supplementarySleepStart();
        LocalDateTime suppEnd = proposed.supplementarySleepEnd();
        if (suppStart != null && suppEnd != null) {
            suppStart = clamp(suppStart, earliest, latest);
            suppEnd = clamp(suppEnd, earliest, latest);
            if (suppEnd.isBefore(suppStart)) {
                suppEnd = suppStart;
            }
        }

        return new SleepBlock(start, end, suppStart, suppEnd, proposed.napMinutes(),
                proposed.adjustToleranceMinutes(), ankerStart, ankerEnd);
    }

    /** 주수면 길이가 규칙 기반 초안보다 너무 짧아진 경우(80% 미만) true — 로그 경고용. */
    public static boolean isMainSleepSuspiciouslyShort(SleepBlock proposed, SleepBlock ruleBased) {
        long proposedMinutes = Duration.between(proposed.mainSleepStart(), proposed.mainSleepEnd()).toMinutes();
        long baselineMinutes = Duration.between(ruleBased.mainSleepStart(), ruleBased.mainSleepEnd()).toMinutes();
        return baselineMinutes > 0 && proposedMinutes < baselineMinutes * 0.8;
    }

    public static LocalTime clampMainMeal(LocalTime candidate, LocalTime bigMealCutoff,
                                           LocalTime nightRestrictionStart, LocalTime nightRestrictionEnd) {
        if (isWithin(candidate, nightRestrictionStart, nightRestrictionEnd)) {
            // 컷오프 자체가 제한 구간 안이면(야간 근무 후 이른 아침에 자는 경우) 구간 끝은 이미 컷오프를 넘긴 시각이다
            return isWithin(bigMealCutoff, nightRestrictionStart, nightRestrictionEnd) ? bigMealCutoff : nightRestrictionEnd;
        }
        long fromRestrictionEndToCandidate = SleepTimeMath.minutesBetween(nightRestrictionEnd, candidate);
        long fromRestrictionEndToCutoff = SleepTimeMath.minutesBetween(nightRestrictionEnd, bigMealCutoff);
        return fromRestrictionEndToCandidate > fromRestrictionEndToCutoff ? bigMealCutoff : candidate;
    }

    private static boolean isWithin(LocalTime t, LocalTime start, LocalTime end) {
        long span = SleepTimeMath.minutesBetween(start, end);
        long offset = SleepTimeMath.minutesBetween(start, t);
        return offset < span;
    }

    private static LocalDateTime clamp(LocalDateTime v, LocalDateTime lo, LocalDateTime hi) {
        if (v.isBefore(lo)) {
            return lo;
        }
        if (v.isAfter(hi)) {
            return hi;
        }
        return v;
    }

    private static LocalDateTime clampToToleranceWindow(LocalDateTime v, LocalDateTime ruleValue, int tolerance,
                                                          LocalDateTime lo, LocalDateTime hi) {
        LocalDateTime low = maxDt(lo, ruleValue.minusMinutes(tolerance));
        LocalDateTime high = minDt(hi, ruleValue.plusMinutes(tolerance));
        return clamp(v, low, high);
    }

    private static LocalDateTime maxDt(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDateTime minDt(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }
}

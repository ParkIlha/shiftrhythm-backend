package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalTime;

/**
 * AI가 절대시각으로 돌려준 수면/식사 제안을 하드 제약(sleepWindow, anchor, mealConstraints)
 * 기준으로 검증하고 벗어난 값을 clamp한다.
 *
 * 시각 비교는 전부 SleepWindow.earliestSleepStart(또는 nightRestrictionStart)를 0분으로 하는
 * 선형 분(minute) 오프셋 공간으로 변환해서 처리한다 — LocalTime은 자정을 넘나들 수 있어 순환
 * 비교가 부정확해지기 쉽기 때문.
 */
public final class AiSleepMealValidator {

    private AiSleepMealValidator() {
    }

    public static SleepBlock clampSleep(SleepBlock proposed, SleepWindow window) {
        LocalTime earliest = window.earliestSleepStart();
        long span = SleepTimeMath.minutesBetween(earliest, window.latestSleepEnd());

        long startOff = clampOffset(SleepTimeMath.minutesBetween(earliest, proposed.mainSleepStart()), span);
        long endOff = clampOffset(SleepTimeMath.minutesBetween(earliest, proposed.mainSleepEnd()), span);
        if (endOff < startOff) {
            endOff = startOff;
        }

        LocalTime ankerStart = proposed.ankerBlockStart();
        LocalTime ankerEnd = proposed.ankerBlockEnd();
        if (ankerStart != null && ankerEnd != null) {
            long ankerStartOff = clampOffset(SleepTimeMath.minutesBetween(earliest, ankerStart), span);
            long ankerEndOff = clampOffset(SleepTimeMath.minutesBetween(earliest, ankerEnd), span);
            // 앵커구간이 수면 밖으로 벗어나면 수면을 앵커를 포함하도록 확장(단, window 밖으로는 못 나감)
            if (ankerStartOff < startOff) {
                startOff = ankerStartOff;
            }
            if (ankerEndOff > endOff) {
                endOff = ankerEndOff;
            }
        }

        LocalTime start = earliest.plusMinutes(startOff);
        LocalTime end = earliest.plusMinutes(endOff);

        LocalTime suppStart = proposed.supplementarySleepStart();
        LocalTime suppEnd = proposed.supplementarySleepEnd();
        if (suppStart != null && suppEnd != null) {
            long suppStartOff = clampOffset(SleepTimeMath.minutesBetween(earliest, suppStart), span);
            long suppEndOff = clampOffset(SleepTimeMath.minutesBetween(earliest, suppEnd), span);
            if (suppEndOff < suppStartOff) {
                suppEndOff = suppStartOff;
            }
            suppStart = earliest.plusMinutes(suppStartOff);
            suppEnd = earliest.plusMinutes(suppEndOff);
        }

        return new SleepBlock(start, end, suppStart, suppEnd, proposed.napMinutes(),
                proposed.adjustToleranceMinutes(), ankerStart, ankerEnd);
    }

    /** 주수면 길이가 규칙 기반 초안보다 너무 짧아진 경우(80% 미만) true — 로그 경고용. */
    public static boolean isMainSleepSuspiciouslyShort(SleepBlock proposed, SleepBlock ruleBased) {
        long proposedMinutes = SleepTimeMath.minutesBetween(proposed.mainSleepStart(), proposed.mainSleepEnd());
        long baselineMinutes = SleepTimeMath.minutesBetween(ruleBased.mainSleepStart(), ruleBased.mainSleepEnd());
        return baselineMinutes > 0 && proposedMinutes < baselineMinutes * 0.8;
    }

    public static LocalTime clampMainMeal(LocalTime candidate, LocalTime bigMealCutoff,
                                           LocalTime nightRestrictionStart, LocalTime nightRestrictionEnd) {
        if (isWithin(candidate, nightRestrictionStart, nightRestrictionEnd)) {
            return nightRestrictionEnd;
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

    private static long clampOffset(long offset, long span) {
        if (offset <= span) {
            return offset;
        }
        long overrunPastLatest = offset - span;
        long distanceBeforeEarliest = 24 * 60 - offset;
        return overrunPastLatest <= distanceBeforeEarliest ? span : 0;
    }
}

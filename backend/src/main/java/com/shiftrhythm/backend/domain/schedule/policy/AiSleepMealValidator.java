package com.shiftrhythm.backend.domain.schedule.policy;
import com.shiftrhythm.backend.domain.schedule.*;
import com.shiftrhythm.backend.domain.schedule.util.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * AI가 절대시각으로 돌려준 수면/식사 제안을 하드 제약(sleepWindow, anchor, mealConstraints)
 * 기준으로 검증하고 벗어난 값을 clamp한다.
 *
 * 수면 쪽은 SleepWindow/SleepBlock이 날짜를 포함한 LocalDateTime이라 순서 비교(isBefore/isAfter)만으로
 * 충분하다. 식사 쪽(clampMeal)은 AI가 날짜 없는 "HH:mm"으로 돌려주므로, mainSleepStart와 가장 가까운
 * 캘린더 날짜에 anchor한 뒤 LocalDateTime으로 비교한다.
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

    /**
     * mainMeal1/mainMeal2 공용 clamp. 근무일/OFF 구분은 이 함수의 책임이 아니다(그건 타임라인
     * 조립 단계에서만 갈린다) — 여기서는 항상 균일하게 두 하드 제약을 적용한다:
     * 1) 수면블록(주+보조) 회피 — 자는 동안 못 먹으니 반드시 하드.
     * 2) applyCutoff가 true면 bigMealCutoff도 하드 적용(간식은 컷오프가 없으므로 false로 호출).
     *
     * candidate는 AI가 "HH:mm"(날짜 없음)으로 돌려준 값이라, mainSleepStart와 가장 가까운 캘린더
     * 날짜로 먼저 anchor한 뒤 판정한다.
     */
    public static LocalTime clampMeal(LocalTime candidate, MealBlock mb, boolean applyCutoff) {
        LocalDateTime anchored = anchorMealTime(candidate, mb.mainSleepStart());
        anchored = avoidInterval(anchored, mb.mainSleepStart(), mb.mainSleepEnd());
        if (mb.supplementarySleepStart() != null && mb.supplementarySleepEnd() != null) {
            anchored = avoidInterval(anchored, mb.supplementarySleepStart(), mb.supplementarySleepEnd());
        }
        if (applyCutoff && anchored.isAfter(mb.bigMealCutoff())) {
            anchored = mb.bigMealCutoff();
        }
        return anchored.toLocalTime();
    }

    /** 날짜 없는 시각을 reference(보통 mainSleepStart)와 가장 가까운 캘린더 날짜에 붙인다. */
    private static LocalDateTime anchorMealTime(LocalTime time, LocalDateTime reference) {
        LocalDateTime same = LocalDateTime.of(reference.toLocalDate(), time);
        LocalDateTime best = same;
        long bestDist = Math.abs(Duration.between(reference, same).toMinutes());
        for (int dayOffset : new int[] {-1, 1}) {
            LocalDateTime candidate = same.plusDays(dayOffset);
            long dist = Math.abs(Duration.between(reference, candidate).toMinutes());
            if (dist < bestDist) {
                best = candidate;
                bestDist = dist;
            }
        }
        return best;
    }

    /** [start, end) 구간 안이면 더 가까운 경계 밖으로 민다. */
    private static LocalDateTime avoidInterval(LocalDateTime candidate, LocalDateTime start, LocalDateTime end) {
        if (candidate.isBefore(start) || !candidate.isBefore(end)) {
            return candidate;
        }
        long toStart = Duration.between(start, candidate).toMinutes();
        long toEnd = Duration.between(candidate, end).toMinutes();
        return toStart <= toEnd ? start : end;
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

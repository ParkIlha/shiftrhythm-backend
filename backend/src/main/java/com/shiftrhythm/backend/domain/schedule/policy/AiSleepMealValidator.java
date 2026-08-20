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
 * 충분하다. 식사 쪽(clampMeal)은 AI가 날짜 없는 "HH:mm"으로 돌려주므로, mainSleepStart와 같은 캘린더
 * 날짜에 anchor한 뒤 LocalDateTime으로 비교한다. 컷오프만은 예외로, anchor된 시각이 오늘의
 * mainSleepStart 이전이면 "오늘 컷오프", 이후(=주수면이 끝난 뒤 다음 근무/자유시간 구간)면
 * "내일 컷오프"를 적용한다 — 자세한 이유는 clampMeal 주석 참고.
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
     * candidate는 AI가 "HH:mm"(날짜 없음)으로 돌려준 값이라, mainSleepStart와 같은 캘린더 날짜에
     * anchor한 뒤 판정한다(NIGHT류는 주수면이 하루 중 이른 시각이라, 주수면 끝난 뒤~보조수면 전
     * 오후~저녁 식사도 "같은 날짜"로 자연스럽게 떨어진다).
     *
     * 컷오프(bigMealCutoff = 오늘 mainSleepStart - 2h)는 "오늘 주수면 시작 전" 식사에만 맞는 기준이다.
     * 주수면이 끝난 뒤(오후~저녁)에 배치된 식사는 그다음으로 다가올 잠이 오늘의 mainSleepStart가
     * 아니라 "내일" mainSleepStart이므로, 그 경우엔 컷오프도 하루 뒤(mb.bigMealCutoff() + 1일)로
     * 옮겨서 비교해야 한다. 이걸 안 하면(예전 버그) 오후에 정상적으로 배치된 식사가 "오늘 새벽
     * 컷오프"에 걸려 엉뚱하게 clamp된다 — mainMeal1/mainMeal2가 둘 다 컷오프 시각으로 붕괴하는
     * 원인이었다.
     */
    public static LocalTime clampMeal(LocalTime candidate, MealBlock mb, boolean applyCutoff) {
        LocalDateTime anchored = LocalDateTime.of(mb.mainSleepStart().toLocalDate(), candidate);
        anchored = avoidInterval(anchored, mb.mainSleepStart(), mb.mainSleepEnd());
        if (mb.supplementarySleepStart() != null && mb.supplementarySleepEnd() != null) {
            anchored = avoidInterval(anchored, mb.supplementarySleepStart(), mb.supplementarySleepEnd());
        }
        if (applyCutoff) {
            LocalDateTime cutoff = anchored.isAfter(mb.mainSleepStart())
                    ? mb.bigMealCutoff().plusDays(1)
                    : mb.bigMealCutoff();
            if (anchored.isAfter(cutoff)) {
                anchored = cutoff;
            }
        }
        return anchored.toLocalTime();
    }

    /**
     * clamp 이후 두 끼가 같은 시각이거나 30분 이내로 붙었으면 true — 컷오프/수면회피가 충돌해서
     * 사실상 한 끼로 뭉개진 것일 수 있으므로 호출부가 경고 로그를 남기는 데 쓴다.
     */
    public static boolean mealsCollapsed(LocalTime mainMeal1, LocalTime mainMeal2) {
        return Math.abs(Duration.between(mainMeal1, mainMeal2).toMinutes()) <= 30;
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

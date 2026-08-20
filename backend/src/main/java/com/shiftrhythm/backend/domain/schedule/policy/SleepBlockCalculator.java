package com.shiftrhythm.backend.domain.schedule.policy;
import com.shiftrhythm.backend.domain.schedule.*;
import com.shiftrhythm.backend.domain.schedule.util.*;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * sleep-block-mode-spec.md 기반 모드별 SleepBlock 계산 디스패처.
 *
 * 각 모드는 SleepBlock(규칙 기반 초기값)과 함께 SleepWindow(AI가 넘을 수 없는 하드 제약)를 반환한다.
 * 근무-근무 사이(오늘 퇴근+통근 ~ 다음 출근-준비-통근)처럼 실제 근무시각에서 유도되는 경계는 그대로
 * 하드 제약으로 쓰고, 휴무일처럼 인접 근무가 없어 유도할 하드 경계가 없는 구간은 그 모드에서 계산한
 * tolerance(이동 허용폭)만큼만 버퍼로 넓혀 AI에게 최소한의 조정 여지를 준다.
 *
 * 모든 시각은 LocalDateTime(날짜 포함)으로 계산한다 — ShiftWindow가 이미 자정을 넘기는 근무의 종료
 * 시각을 다음날 날짜로 계산해서 넘겨주므로, 이 클래스는 자정 넘김을 별도로 신경 쓸 필요가 없다.
 *
 * 스펙에 정확한 산식이 없는 일부 지점(전환/휴무 모드의 "앵커와 최대 overlap" 최적화,
 * 기준블록이 아예 없을 때의 완전 폴백 값)은 우선순위 규칙(근무시작종료 > prep > commute >
 * 확보가능한 주수면 > 앵커 overlap > 목표수면충족 > rhythmPreference)에 맞춰 합리적으로
 * 단순화했다.
 */
public final class SleepBlockCalculator {

    private static final int DEFAULT_TOLERANCE = 60;
    private static final int NAP_CAP_MINUTES = 30;
    private static final int RECOVERY_FIRST_TOLERANCE = 90;
    private static final int RECOVERY_MID_TOLERANCE = 30;
    private static final int RECOVERY_MAX_BONUS_MINUTES = 90;
    private static final int DEFICIT_EXTENSION_CAP_MINUTES = 60;
    /** prevSleepBlock/Anchor가 전혀 없을 때(등록 초반 등)의 최후 폴백 취침 시각. */
    private static final LocalTime FALLBACK_SLEEP_START_TIME = LocalTime.of(23, 0);

    private SleepBlockCalculator() {
    }

    public static SleepBlockResult calculate(SleepBlockContext ctx) {
        return switch (ctx.mode()) {
            case DAY -> stableDay(ctx);
            case EVENING -> stableEvening(ctx);
            case NIGHT -> stableNight(ctx);
            case SHIFT_TRANSITION -> transition(ctx);
            case OFF_RHYTHM_MAINTAIN -> offMaintain(ctx);
            case OFF_RHYTHM_SHIFT -> offShift(ctx);
            case OFF_RECOVERY -> offRecovery(ctx);
        };
    }

    // ---- 안정 모드 ----

    private static SleepBlockResult stableDay(SleepBlockContext ctx) {
        SleepBlock block = wakeAnchoredBlock(ctx.next().startTime(), ctx.prepMinutes(), ctx.commuteMinutes(), ctx.targetSleepMinutes());
        // 오늘 퇴근시각이 없는 안정 DAY라 하한을 강제할 근무 경계가 없음 → tolerance만큼 버퍼
        return bufferedBelow(block);
    }

    private static SleepBlockResult stableEvening(SleepBlockContext ctx) {
        LocalDateTime windowStart = ctx.today().endTime().plusMinutes(ctx.commuteMinutes());
        LocalDateTime windowEnd = ctx.next().startTime().minusMinutes(ctx.prepMinutes() + ctx.commuteMinutes());
        SleepBlock block = windowFrontBlockWithNap(windowStart, windowEnd, ctx.targetSleepMinutes(), ctx.napAvailable(), ctx.napAvailableMinutes());
        return new SleepBlockResult(block, new SleepWindow(windowStart, windowEnd));
    }

    private static SleepBlockResult stableNight(SleepBlockContext ctx) {
        LocalDateTime windowStart = ctx.today().endTime().plusMinutes(ctx.commuteMinutes());
        LocalDateTime windowEnd = ctx.next().startTime().minusMinutes(ctx.prepMinutes() + ctx.commuteMinutes());
        SleepBlock block = NightSplitAllocator.allocate(windowStart, windowEnd, ctx.targetSleepMinutes(), ctx.napAvailable(), ctx.napAvailableMinutes());
        return new SleepBlockResult(block, new SleepWindow(windowStart, windowEnd));
    }

    // ---- 근무 전환 ----

    private static SleepBlockResult transition(SleepBlockContext ctx) {
        ShiftType t = ctx.today().type();
        ShiftType n = ctx.next().type();

        if (t == ShiftType.NIGHT && n == ShiftType.DAY) {
            throw new InvalidShiftTransitionException(t, n);
        }

        Anchor anchor = AnchorSizer.size(ctx.prevSleepBlock(), ctx.rhythmPreference());

        if (t == ShiftType.NIGHT && n == ShiftType.EVENING) {
            LocalDateTime windowStart = ctx.today().endTime().plusMinutes(ctx.commuteMinutes());
            LocalDateTime windowEnd = ctx.next().startTime().minusMinutes(ctx.prepMinutes() + ctx.commuteMinutes());
            SleepBlock split = NightSplitAllocator.allocate(windowStart, windowEnd, ctx.targetSleepMinutes(), ctx.napAvailable(), ctx.napAvailableMinutes());
            return new SleepBlockResult(split.withAnchor(anchor), new SleepWindow(windowStart, windowEnd));
        }

        if (t == ShiftType.EVENING && n == ShiftType.DAY) {
            LocalDateTime earliest = ctx.today().endTime().plusMinutes(ctx.commuteMinutes());
            LocalDateTime deadline = ctx.next().startTime().minusMinutes(ctx.prepMinutes() + ctx.commuteMinutes());
            SleepBlock block = deadlineAnchoredBlock(earliest, deadline, ctx.targetSleepMinutes());
            return new SleepBlockResult(block.withAnchor(anchor), new SleepWindow(earliest, deadline));
        }

        // 정방향: DAY->EVENING, EVENING->NIGHT, DAY->NIGHT
        LocalDateTime windowStart = ctx.today().endTime().plusMinutes(ctx.commuteMinutes());
        LocalDateTime windowEnd = ctx.next().startTime().minusMinutes(ctx.prepMinutes() + ctx.commuteMinutes());
        SleepBlock block = windowFrontBlockNoNap(windowStart, windowEnd, ctx.targetSleepMinutes());
        return new SleepBlockResult(block.withAnchor(anchor), new SleepWindow(windowStart, windowEnd));
    }

    // ---- 휴무: 리듬 유지 ----

    private static SleepBlockResult offMaintain(SleepBlockContext ctx) {
        ShiftType maintained = ctx.next().type();
        if (maintained == ShiftType.DAY) {
            SleepBlock block = wakeAnchoredBlock(ctx.next().startTime(), ctx.prepMinutes(), ctx.commuteMinutes(), ctx.targetSleepMinutes());
            return bufferedBelow(block);
        }

        Anchor anchor = AnchorSizer.size(ctx.prevSleepBlock(), ctx.rhythmPreference());
        if (anchor == null) {
            SleepBlock block = fallbackBlock(ctx.date(), ctx.targetSleepMinutes(), DEFAULT_TOLERANCE, null);
            return bufferedBoth(block);
        }

        SleepBlock block = new SleepBlock(anchor.start(), anchor.end(), null, null, null, DEFAULT_TOLERANCE, anchor.start(), anchor.end());

        long baseLength = SleepTimeMath.minutesBetween(ctx.prevSleepBlock().mainSleepStart(), ctx.prevSleepBlock().mainSleepEnd());
        if (baseLength > 0) {
            int anchorMinutes = AnchorSizer.anchorMinutesFor(ctx.rhythmPreference());
            double overlapRatio = anchorMinutes / (double) baseLength;
            double moveRange = (1 - overlapRatio) * baseLength / 2.0;
            block = block.withTolerance((int) Math.round(Math.max(moveRange, 0)));
        }
        return bufferedBoth(block);
    }

    // ---- 휴무: 리듬 전환 ----

    private static SleepBlockResult offShift(SleepBlockContext ctx) {
        Anchor anchor = AnchorSizer.size(ctx.prevSleepBlock(), ctx.rhythmPreference());
        SleepBlock targetBlock = wakeAnchoredBlock(ctx.next().startTime(), ctx.prepMinutes(), ctx.commuteMinutes(), ctx.targetSleepMinutes());

        if (ctx.prevSleepBlock() == null || ctx.offDayTotal() <= 0) {
            return bufferedBelow(targetBlock.withAnchor(anchor));
        }

        LocalDateTime current = ctx.prevSleepBlock().mainSleepStart();
        LocalDateTime target = targetBlock.mainSleepStart();

        // 리듬 전환은 "시각(time-of-day)"을 하루하루 조금씩 옮기는 것이지, current/target의 실제
        // 날짜 차이를 옮기는 게 아니다 — 그래서 이동 방향/거리는 시각만의 순환 거리로 판단한다.
        long forward = SleepTimeMath.minutesBetween(current.toLocalTime(), target.toLocalTime());
        long backward = SleepTimeMath.minutesBetween(target.toLocalTime(), current.toLocalTime());
        boolean movingForward = forward <= backward;
        long totalMove = Math.min(forward, backward);

        double baseStep = totalMove / (double) ctx.offDayTotal();
        int effectiveTarget = ctx.targetSleepMinutes();
        if (ctx.recentSleepDeficitMinutes() > 0) {
            baseStep = baseStep / 2.0;
            effectiveTarget += Math.min(ctx.recentSleepDeficitMinutes(), DEFICIT_EXTENSION_CAP_MINUTES);
        }

        int stepMinutes = (int) Math.round(baseStep);
        LocalDateTime newStart = movingForward ? current.plusMinutes(stepMinutes) : current.minusMinutes(stepMinutes);
        LocalDateTime newEnd = newStart.plusMinutes(effectiveTarget);

        SleepBlock block = new SleepBlock(newStart, newEnd, null, null, null, DEFAULT_TOLERANCE,
                anchor == null ? null : anchor.start(), anchor == null ? null : anchor.end());
        return bufferedBoth(block);
    }

    // ---- 휴무: 회복 ----

    private static SleepBlockResult offRecovery(SleepBlockContext ctx) {
        if (ctx.offDayIndex() <= 0) {
            throw new IllegalArgumentException("OFF_RECOVERY 계산에는 offDayIndex(1부터)가 필요합니다");
        }
        Anchor anchor = AnchorSizer.size(ctx.prevSleepBlock(), ctx.rhythmPreference());

        // 다음 근무 시작시각을 아는 경우에만 거기 맞춰 기상시각을 역산한다. 등록된 근무표 범위를 벗어나
        // 다음 근무일을 아직 모르면(next가 OFF 폴백이라 startTime==null) 역산할 기준이 없으므로
        // 아래의 앵커 기반 계산으로 넘어간다 — 그대로 두면 NPE로 500이 났었다.
        if (ctx.offDayIndex() == ctx.offDayTotal() && ctx.next() != null && ctx.next().startTime() != null) {
            SleepBlock block = wakeAnchoredBlock(ctx.next().startTime(), ctx.prepMinutes(), ctx.commuteMinutes(), ctx.targetSleepMinutes());
            return bufferedBelow(block.withAnchor(anchor));
        }

        LocalDateTime baseStart = anchor != null ? anchor.start()
                : ctx.prevSleepBlock() != null ? ctx.prevSleepBlock().mainSleepStart()
                : LocalDateTime.of(ctx.date(), FALLBACK_SLEEP_START_TIME);

        if (ctx.offDayIndex() == 1) {
            int bonus = Math.min(Math.max(ctx.recentSleepDeficitMinutes(), 0), RECOVERY_MAX_BONUS_MINUTES);
            int effectiveTarget = ctx.targetSleepMinutes() + bonus;
            LocalDateTime end = baseStart.plusMinutes(effectiveTarget);
            SleepBlock block = new SleepBlock(baseStart, end, null, null, null, RECOVERY_FIRST_TOLERANCE,
                    anchor == null ? null : anchor.start(), anchor == null ? null : anchor.end());
            return bufferedBoth(block);
        }

        LocalDateTime end = anchor != null ? anchor.end() : baseStart.plusMinutes(ctx.targetSleepMinutes());
        SleepBlock block = new SleepBlock(baseStart, end, null, null, null, RECOVERY_MID_TOLERANCE,
                anchor == null ? null : anchor.start(), anchor == null ? null : anchor.end());
        return bufferedBoth(block);
    }

    // ---- 공통 헬퍼 ----

    private static SleepBlock wakeAnchoredBlock(LocalDateTime nextShiftStart, int prepMinutes, int commuteMinutes, int targetSleepMinutes) {
        LocalDateTime wakeTarget = nextShiftStart.minusMinutes(prepMinutes + commuteMinutes);
        LocalDateTime sleepStart = wakeTarget.minusMinutes(targetSleepMinutes);
        return new SleepBlock(sleepStart, wakeTarget, null, null, null, DEFAULT_TOLERANCE, null, null);
    }

    private static SleepBlock deadlineAnchoredBlock(LocalDateTime earliest, LocalDateTime deadline, int targetSleepMinutes) {
        long available = SleepTimeMath.minutesBetween(earliest, deadline);
        int sleepMinutes = (int) Math.min(targetSleepMinutes, Math.max(available, 0));
        LocalDateTime start = deadline.minusMinutes(sleepMinutes);
        return new SleepBlock(start, deadline, null, null, null, DEFAULT_TOLERANCE, null, null);
    }

    private static SleepBlock windowFrontBlockWithNap(LocalDateTime windowStart, LocalDateTime windowEnd, int targetSleepMinutes,
                                                        boolean napAvailable, Integer napAvailableMinutes) {
        long available = SleepTimeMath.minutesBetween(windowStart, windowEnd);
        Integer napMinutes = null;
        LocalDateTime end;
        if (available >= targetSleepMinutes) {
            end = windowStart.plusMinutes(targetSleepMinutes);
        } else {
            end = windowEnd;
            int shortage = (int) (targetSleepMinutes - available);
            if (shortage > 0 && napAvailable) {
                int cap = napAvailableMinutes == null ? NAP_CAP_MINUTES : Math.min(napAvailableMinutes, NAP_CAP_MINUTES);
                napMinutes = Math.min(shortage, cap);
            }
        }
        return new SleepBlock(windowStart, end, null, null, napMinutes, DEFAULT_TOLERANCE, null, null);
    }

    private static SleepBlock windowFrontBlockNoNap(LocalDateTime windowStart, LocalDateTime windowEnd, int targetSleepMinutes) {
        long available = SleepTimeMath.minutesBetween(windowStart, windowEnd);
        int sleepMinutes = (int) Math.min(targetSleepMinutes, Math.max(available, 0));
        LocalDateTime end = windowStart.plusMinutes(sleepMinutes);
        return new SleepBlock(windowStart, end, null, null, null, DEFAULT_TOLERANCE, null, null);
    }

    private static SleepBlock fallbackBlock(java.time.LocalDate date, int targetSleepMinutes, int tolerance, Anchor anchor) {
        LocalDateTime start = LocalDateTime.of(date, FALLBACK_SLEEP_START_TIME);
        LocalDateTime end = start.plusMinutes(targetSleepMinutes);
        return new SleepBlock(start, end, null, null, null, tolerance,
                anchor == null ? null : anchor.start(), anchor == null ? null : anchor.end());
    }

    /** 하한(취침시작 이전)만 근무 경계가 없는 경우 — tolerance만큼만 버퍼, 상한(기상시각)은 그대로 하드 유지. */
    private static SleepBlockResult bufferedBelow(SleepBlock block) {
        LocalDateTime earliest = block.mainSleepStart().minusMinutes(block.adjustToleranceMinutes());
        return new SleepBlockResult(block, new SleepWindow(earliest, block.mainSleepEnd()));
    }

    /** 상하한 모두 근무 경계가 없는 휴무일 — 양쪽 다 tolerance만큼 버퍼. */
    private static SleepBlockResult bufferedBoth(SleepBlock block) {
        LocalDateTime earliest = block.mainSleepStart().minusMinutes(block.adjustToleranceMinutes());
        LocalDateTime latest = block.mainSleepEnd().plusMinutes(block.adjustToleranceMinutes());
        return new SleepBlockResult(block, new SleepWindow(earliest, latest));
    }
}

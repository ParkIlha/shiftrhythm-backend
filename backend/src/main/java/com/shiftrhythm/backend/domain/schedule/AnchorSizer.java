package com.shiftrhythm.backend.domain.schedule;

/**
 * 기존 수면 리듬(prevSleepBlock)과 새로 배치하려는 수면 리듬 사이에서
 * 공통으로 유지할 수 있는 수면 구간(앵커)을 계산한다.
 * prevSleepBlock이 없으면 앵커 계산을 생략(null)한다.
 */
public final class AnchorSizer {

    private AnchorSizer() {
    }

    public static int anchorMinutesFor(RhythmPreference preference) {
        return switch (preference) {
            case RHYTHM_LEAN -> 360;
            case BALANCED -> 300;
            case DAY_LEAN -> 240;
        };
    }

    public static Anchor size(SleepBlock prevSleepBlock, RhythmPreference preference) {
        if (prevSleepBlock == null) {
            return null;
        }
        int anchorMinutes = anchorMinutesFor(preference);
        var start = prevSleepBlock.mainSleepStart();
        return new Anchor(start, start.plusMinutes(anchorMinutes));
    }
}

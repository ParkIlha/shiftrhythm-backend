package com.shiftrhythm.backend.domain.schedule;

/**
 * NIGHT → DAY 등 정상적인 수면·회복시간을 확보할 수 없는 금지 전환.
 */
public class InvalidShiftTransitionException extends RuntimeException {
    public InvalidShiftTransitionException(ShiftType from, ShiftType to) {
        super("INVALID_SHIFT_TRANSITION: " + from + " -> " + to);
    }
}

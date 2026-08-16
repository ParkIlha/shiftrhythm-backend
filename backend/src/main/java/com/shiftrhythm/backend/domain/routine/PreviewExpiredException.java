package com.shiftrhythm.backend.domain.routine;

public class PreviewExpiredException extends RuntimeException {
    public PreviewExpiredException() {
        super("PREVIEW_EXPIRED");
    }
}

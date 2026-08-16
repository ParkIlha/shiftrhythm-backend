package com.shiftrhythm.backend.domain.routine;

public class ParseFailedException extends RuntimeException {
    public ParseFailedException() {
        super("PARSE_FAILED");
    }
}

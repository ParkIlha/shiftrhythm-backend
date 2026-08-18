package com.shiftrhythm.backend.domain.routine;

public class ParseFailedException extends RuntimeException {

    /** AI가 답을 못 준 경우의 기본 문구 — 그대로 사용자에게 보인다. */
    private static final String DEFAULT_MESSAGE = "근무 관련 내용으로 다시 입력해주세요";

    public ParseFailedException() {
        this(DEFAULT_MESSAGE);
    }

    public ParseFailedException(String message) {
        super(message);
    }
}

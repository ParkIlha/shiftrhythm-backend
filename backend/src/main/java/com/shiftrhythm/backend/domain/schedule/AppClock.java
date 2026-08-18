package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * "오늘"을 판정하는 모든 곳은 서버/컨테이너의 기본 타임존에 기대지 않고 이걸 통해 KST로 명시한다.
 */
public final class AppClock {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private AppClock() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(KST);
    }
}

package com.shiftrhythm.backend.domain.schedule.entity;

import com.shiftrhythm.backend.domain.schedule.RhythmPreference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 로그인 없음 — 클라이언트가 X-User-Id 헤더로 자기 프로필을 지목한다(CurrentUser 참고).
 * "새로 시작하기"는 헤더 없이 프로필을 등록해 새 행을 발급받는 것을 뜻한다.
 * id=1은 데모 시드 사용자로, 헤더가 없을 때의 기본값이기도 하다.
 * 테이블명은 Spring의 기본 네이밍 전략(CamelCase -> snake_case)을 따른다 (user_profile).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class UserProfile {

    /** 데모 시드 사용자 id. X-User-Id 헤더가 없을 때의 기본값이다. */
    public static final long SINGLETON_ID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String name;

    private int commuteMinutes;
    private int prepMinutes;
    private int targetSleepMinutes = 420;
    private boolean napAvailable;
    private Integer napAvailableMinutes;

    @Enumerated(EnumType.STRING)
    private RhythmPreference rhythmPreference;

    public UserProfile(String name, int commuteMinutes, int prepMinutes, int targetSleepMinutes,
                        boolean napAvailable, Integer napAvailableMinutes, RhythmPreference rhythmPreference) {
        this.name = name;
        this.commuteMinutes = commuteMinutes;
        this.prepMinutes = prepMinutes;
        this.targetSleepMinutes = targetSleepMinutes;
        this.napAvailable = napAvailable;
        this.napAvailableMinutes = napAvailableMinutes;
        this.rhythmPreference = rhythmPreference;
    }
}

package com.shiftrhythm.backend.domain.schedule.entity;

import com.shiftrhythm.backend.domain.schedule.RhythmPreference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 로그인 없음(단일세션) 전제 — 항상 id=1인 단일 행만 존재한다(고정 PK, auto-increment 아님).
 * 테이블명은 Spring의 기본 네이밍 전략(CamelCase -> snake_case)을 따른다 (user_profile).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class UserProfile {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

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

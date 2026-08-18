package com.shiftrhythm.backend.domain.routine.entity;

import com.shiftrhythm.backend.domain.routine.MealTimes;
import com.shiftrhythm.backend.domain.routine.ReplanReason;
import com.shiftrhythm.backend.domain.schedule.RoutineMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * append-only 버전 구조. 재설계마다 새 row(version+1)를 만들고 is_current를 옮긴다.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoutineResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userProfileId;
    private LocalDate date;
    private int version = 1;
    private boolean isCurrent;

    @Enumerated(EnumType.STRING)
    private ReplanReason replanReason;

    @Enumerated(EnumType.STRING)
    private RoutineMode mode;

    private LocalTime sleepStart;
    private LocalTime sleepEnd;
    private LocalTime supplementarySleepStart;
    private LocalTime supplementarySleepEnd;
    private Integer napMinutes;

    /** 재설계로 실제 근무 종료시각이 바뀐 경우에만 채워진다(SHIFT_END_DELAY/SHIFT_ADDED). 그 외엔 null — 원본 근무표 시각을 그대로 쓴다. */
    private LocalTime adjustedShiftEndTime;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private MealTimes mealTimes;

    @Column(columnDefinition = "text")
    private String aiReason;

    private LocalDateTime aiUpdatedAt;

    public RoutineResult(Long userProfileId, LocalDate date, int version, boolean isCurrent,
                          ReplanReason replanReason, RoutineMode mode,
                          LocalTime sleepStart, LocalTime sleepEnd,
                          LocalTime supplementarySleepStart, LocalTime supplementarySleepEnd,
                          Integer napMinutes, MealTimes mealTimes) {
        this.userProfileId = userProfileId;
        this.date = date;
        this.version = version;
        this.isCurrent = isCurrent;
        this.replanReason = replanReason;
        this.mode = mode;
        this.sleepStart = sleepStart;
        this.sleepEnd = sleepEnd;
        this.supplementarySleepStart = supplementarySleepStart;
        this.supplementarySleepEnd = supplementarySleepEnd;
        this.napMinutes = napMinutes;
        this.mealTimes = mealTimes;
    }
}

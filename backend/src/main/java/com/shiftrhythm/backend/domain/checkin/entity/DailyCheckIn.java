package com.shiftrhythm.backend.domain.checkin.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyCheckIn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userProfileId;
    private LocalDate date;

    private Integer conditionScore;
    private Integer sleepSatisfaction;
    private Integer sleepLatencyMinutes;
    private LocalTime actualClockOut;
    private Integer nightHungerScore;

    private LocalDateTime updatedAt;

    public DailyCheckIn(Long userProfileId, LocalDate date) {
        this.userProfileId = userProfileId;
        this.date = date;
        this.updatedAt = LocalDateTime.now();
    }

    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}

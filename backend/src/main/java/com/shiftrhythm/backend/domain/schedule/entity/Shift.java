package com.shiftrhythm.backend.domain.schedule.entity;

import com.shiftrhythm.backend.domain.schedule.ShiftType;
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

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * date는 "근무 시작일" 기준(자정을 넘겨도 시작일로 저장).
 * start/endTimeOverride가 null이면 조회 시 ShiftTypeDefault 값을 사용한다.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userProfileId;
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    private ShiftType shiftType;

    private LocalTime startTimeOverride;
    private LocalTime endTimeOverride;

    public Shift(Long userProfileId, LocalDate date, ShiftType shiftType, LocalTime startTimeOverride, LocalTime endTimeOverride) {
        this.userProfileId = userProfileId;
        this.date = date;
        this.shiftType = shiftType;
        this.startTimeOverride = startTimeOverride;
        this.endTimeOverride = endTimeOverride;
    }
}

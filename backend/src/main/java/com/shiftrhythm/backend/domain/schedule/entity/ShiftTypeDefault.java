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

import java.time.LocalTime;

@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShiftTypeDefault {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userProfileId;

    @Enumerated(EnumType.STRING)
    private ShiftType shiftType;

    private LocalTime defaultStartTime;
    private LocalTime defaultEndTime;

    public ShiftTypeDefault(Long userProfileId, ShiftType shiftType, LocalTime defaultStartTime, LocalTime defaultEndTime) {
        this.userProfileId = userProfileId;
        this.shiftType = shiftType;
        this.defaultStartTime = defaultStartTime;
        this.defaultEndTime = defaultEndTime;
    }
}

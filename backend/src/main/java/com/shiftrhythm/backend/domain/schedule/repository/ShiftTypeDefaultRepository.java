package com.shiftrhythm.backend.domain.schedule.repository;

import com.shiftrhythm.backend.domain.schedule.ShiftType;
import com.shiftrhythm.backend.domain.schedule.entity.ShiftTypeDefault;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ShiftTypeDefaultRepository extends JpaRepository<ShiftTypeDefault, Long> {
    List<ShiftTypeDefault> findByUserProfileId(Long userProfileId);

    Optional<ShiftTypeDefault> findByUserProfileIdAndShiftType(Long userProfileId, ShiftType shiftType);

    void deleteByUserProfileId(Long userProfileId);
}

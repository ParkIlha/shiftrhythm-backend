package com.shiftrhythm.backend.domain.schedule.repository;

import com.shiftrhythm.backend.domain.schedule.entity.Shift;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ShiftRepository extends JpaRepository<Shift, Long> {
    List<Shift> findByUserProfileIdOrderByDateAsc(Long userProfileId);

    List<Shift> findByUserProfileIdAndDateBetweenOrderByDateAsc(Long userProfileId, LocalDate from, LocalDate to);

    Optional<Shift> findByUserProfileIdAndDate(Long userProfileId, LocalDate date);
}

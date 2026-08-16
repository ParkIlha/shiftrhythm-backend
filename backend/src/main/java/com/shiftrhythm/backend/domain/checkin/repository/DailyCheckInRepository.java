package com.shiftrhythm.backend.domain.checkin.repository;

import com.shiftrhythm.backend.domain.checkin.entity.DailyCheckIn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyCheckInRepository extends JpaRepository<DailyCheckIn, Long> {
    Optional<DailyCheckIn> findByUserProfileIdAndDate(Long userProfileId, LocalDate date);

    List<DailyCheckIn> findByUserProfileIdAndDateBetween(Long userProfileId, LocalDate from, LocalDate to);
}

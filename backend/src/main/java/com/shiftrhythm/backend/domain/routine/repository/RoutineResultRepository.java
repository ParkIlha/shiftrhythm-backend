package com.shiftrhythm.backend.domain.routine.repository;

import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RoutineResultRepository extends JpaRepository<RoutineResult, Long> {
    Optional<RoutineResult> findByUserProfileIdAndDateAndIsCurrentTrue(Long userProfileId, LocalDate date);

    List<RoutineResult> findByUserProfileIdAndDateInAndIsCurrentTrue(Long userProfileId, List<LocalDate> dates);

    Optional<RoutineResult> findFirstByUserProfileIdAndIsCurrentTrueAndDateLessThanOrderByDateDesc(
            Long userProfileId, LocalDate date);

    List<RoutineResult> findByUserProfileIdAndDateBetweenAndIsCurrentTrueOrderByDateAsc(
            Long userProfileId, LocalDate from, LocalDate to);

    Optional<RoutineResult> findFirstByUserProfileIdAndDateOrderByVersionDesc(Long userProfileId, LocalDate date);

    Optional<RoutineResult> findByUserProfileIdAndDateAndVersion(Long userProfileId, LocalDate date, int version);

    List<RoutineResult> findByUserProfileIdAndDateOrderByVersionAsc(Long userProfileId, LocalDate date);

    /** 재계획으로 생성된(version>1) 행만 조회 — 리포트의 재계획 집계용. */
    List<RoutineResult> findByUserProfileIdAndDateBetweenAndVersionGreaterThanOrderByDateAscVersionAsc(
            Long userProfileId, LocalDate from, LocalDate to, int version);
}

package com.shiftrhythm.backend.domain.schedule.repository;

import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
}

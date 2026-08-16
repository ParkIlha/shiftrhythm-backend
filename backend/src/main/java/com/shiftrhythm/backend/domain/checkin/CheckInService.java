package com.shiftrhythm.backend.domain.checkin;

import com.shiftrhythm.backend.domain.checkin.entity.DailyCheckIn;
import com.shiftrhythm.backend.domain.checkin.repository.DailyCheckInRepository;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * DailyCheckIn 저장만 담당. AI 호출 없음 — AI 재호출 여부는 RoutineFacade가 updated_at을 보고 판단한다.
 */
@Service
public class CheckInService {

    private final DailyCheckInRepository repository;

    public CheckInService(DailyCheckInRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public DailyCheckIn wake(LocalDate date, Integer conditionScore, Integer sleepSatisfaction, Integer sleepLatencyMinutes) {
        DailyCheckIn checkIn = findOrCreate(date);
        if (conditionScore != null) {
            checkIn.setConditionScore(conditionScore);
        }
        if (sleepSatisfaction != null) {
            checkIn.setSleepSatisfaction(sleepSatisfaction);
        }
        if (sleepLatencyMinutes != null) {
            checkIn.setSleepLatencyMinutes(sleepLatencyMinutes);
        }
        checkIn.touch();
        return repository.save(checkIn);
    }

    @Transactional
    public DailyCheckIn clockout(LocalDate date, LocalTime actualClockOut, Integer nightHungerScore) {
        DailyCheckIn checkIn = findOrCreate(date);
        checkIn.setActualClockOut(actualClockOut);
        if (nightHungerScore != null) {
            checkIn.setNightHungerScore(nightHungerScore);
        }
        checkIn.touch();
        return repository.save(checkIn);
    }

    private DailyCheckIn findOrCreate(LocalDate date) {
        return repository.findByUserProfileIdAndDate(UserProfile.SINGLETON_ID, date)
                .orElseGet(() -> new DailyCheckIn(UserProfile.SINGLETON_ID, date));
    }
}

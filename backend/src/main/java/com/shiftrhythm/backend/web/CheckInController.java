package com.shiftrhythm.backend.web;

import com.shiftrhythm.backend.domain.checkin.CheckInService;
import com.shiftrhythm.backend.domain.checkin.entity.DailyCheckIn;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import com.shiftrhythm.backend.domain.schedule.repository.ShiftRepository;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;

@RestController
public class CheckInController {

    private final CheckInService checkInService;
    private final ShiftRepository shiftRepository;

    public CheckInController(CheckInService checkInService, ShiftRepository shiftRepository) {
        this.checkInService = checkInService;
        this.shiftRepository = shiftRepository;
    }

    public record WakeRequest(@NotNull LocalDate date, Integer conditionScore, Integer sleepSatisfaction, Integer sleepLatencyMinutes) {
    }

    public record WakeResponse(boolean ok, LocalDate date) {
    }

    public record ClockOutRequest(@NotNull LocalDate date, @NotNull LocalTime actualClockOut, Integer nightHungerScore) {
    }

    public record ClockOutResponse(boolean ok, LocalDate date, LocalTime scheduledClockOut, long delayMinutes) {
    }

    @PostMapping("/api/checkins/wake")
    public WakeResponse wake(@RequestBody WakeRequest request) {
        checkInService.wake(request.date(), request.conditionScore(), request.sleepSatisfaction(), request.sleepLatencyMinutes());
        return new WakeResponse(true, request.date());
    }

    @PostMapping("/api/checkins/clockout")
    public ClockOutResponse clockout(@RequestBody ClockOutRequest request) {
        DailyCheckIn saved = checkInService.clockout(request.date(), request.actualClockOut(), request.nightHungerScore());

        LocalTime scheduledClockOut = shiftRepository
                .findByUserProfileIdAndDate(UserProfile.SINGLETON_ID, request.date())
                .map(s -> s.getEndTimeOverride())
                .orElse(null);
        long delayMinutes = 0;
        if (scheduledClockOut != null) {
            delayMinutes = java.time.Duration.between(scheduledClockOut, saved.getActualClockOut()).toMinutes();
        }
        return new ClockOutResponse(true, request.date(), scheduledClockOut, delayMinutes);
    }
}

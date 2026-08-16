package com.shiftrhythm.backend.web;

import com.shiftrhythm.backend.domain.routine.OnboardingService;
import com.shiftrhythm.backend.domain.schedule.RhythmPreference;
import com.shiftrhythm.backend.domain.schedule.ShiftType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@RestController
public class OnboardingController {

    private final OnboardingService onboardingService;

    public OnboardingController(OnboardingService onboardingService) {
        this.onboardingService = onboardingService;
    }

    public record ShiftTypeDefaultDto(@NotNull ShiftType shiftType, @NotNull LocalTime startTime, @NotNull LocalTime endTime) {
    }

    public record ProfileRequest(
            int commuteMinutes,
            int prepMinutes,
            int targetSleepMinutes,
            boolean napAvailable,
            Integer napAvailableMinutes,
            @NotNull RhythmPreference rhythmPreference,
            @NotNull List<ShiftTypeDefaultDto> shiftTypeDefaults
    ) {
    }

    public record ShiftDto(@NotNull LocalDate date, @NotNull ShiftType shiftType) {
    }

    public record ScheduleRequest(@NotNull List<ShiftDto> shifts) {
    }

    public record OkResponse(boolean ok) {
    }

    @PostMapping("/api/onboarding/profile")
    public OkResponse profile(@Valid @RequestBody ProfileRequest request) {
        List<OnboardingService.ShiftTypeDefaultInput> defaults = request.shiftTypeDefaults().stream()
                .map(d -> new OnboardingService.ShiftTypeDefaultInput(d.shiftType(), d.startTime(), d.endTime()))
                .toList();
        onboardingService.upsertProfile(request.commuteMinutes(), request.prepMinutes(), request.targetSleepMinutes(),
                request.napAvailable(), request.napAvailableMinutes(), request.rhythmPreference(), defaults);
        return new OkResponse(true);
    }

    @PostMapping("/api/onboarding/schedule")
    public OkResponse schedule(@Valid @RequestBody ScheduleRequest request) {
        List<OnboardingService.ShiftInput> shifts = request.shifts().stream()
                .map(s -> new OnboardingService.ShiftInput(s.date(), s.shiftType()))
                .toList();
        onboardingService.registerSchedule(shifts);
        return new OkResponse(true);
    }
}

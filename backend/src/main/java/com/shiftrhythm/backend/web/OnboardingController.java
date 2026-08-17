package com.shiftrhythm.backend.web;

import com.shiftrhythm.backend.domain.ai.AiScheduleAdapter;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleRequest;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleResponse;
import com.shiftrhythm.backend.domain.routine.OnboardingService;
import com.shiftrhythm.backend.domain.routine.ParseFailedException;
import com.shiftrhythm.backend.domain.schedule.RhythmPreference;
import com.shiftrhythm.backend.domain.schedule.ShiftType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    private final AiScheduleAdapter aiScheduleAdapter;

    public OnboardingController(OnboardingService onboardingService, AiScheduleAdapter aiScheduleAdapter) {
        this.onboardingService = onboardingService;
        this.aiScheduleAdapter = aiScheduleAdapter;
    }

    public record ParseScheduleApiRequest(@NotBlank String imageBase64, String myRowLabel) {
    }

    /**
     * 근무표 사진을 AI로 파싱한다. 사진에 여러 명의 행이 있어 AI가 사용자 본인 행을 특정하지
     * 못하면 RowLabelRequiredException(422 ROW_LABEL_REQUIRED + 행 목록)이 던져지고,
     * ApiExceptionHandler가 이를 프론트가 선택 UI를 띄울 수 있는 형태로 응답한다. 프론트는
     * 사용자가 고른 라벨을 myRowLabel에 담아 이 엔드포인트를 다시 호출하면 된다.
     * 이 엔드포인트는 파싱 결과만 반환하며, 실제 등록은 여전히 /api/onboarding/schedule을 통해 이뤄진다.
     */
    @PostMapping("/api/onboarding/schedule/parse")
    public ParseScheduleResponse parseSchedule(@Valid @RequestBody ParseScheduleApiRequest request) {
        return aiScheduleAdapter.parseSchedule(new ParseScheduleRequest(request.imageBase64(), request.myRowLabel()))
                .orElseThrow(ParseFailedException::new);
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

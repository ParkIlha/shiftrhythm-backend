package com.shiftrhythm.backend.web;

import com.shiftrhythm.backend.domain.ai.AiScheduleAdapter;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleRequest;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleResponse;
import com.shiftrhythm.backend.domain.routine.OnboardingService;
import com.shiftrhythm.backend.domain.routine.ParseFailedException;
import com.shiftrhythm.backend.domain.schedule.RhythmPreference;
import com.shiftrhythm.backend.domain.schedule.ShiftType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@RestController
@Tag(name = "온보딩", description = "최초 실행 시(또는 '새로 시작하기' 후) 개인화 데이터 등록 → 근무표 사진 파싱 → 근무표 확정 등록까지의 흐름.")
public class OnboardingController {

    private final OnboardingService onboardingService;
    private final AiScheduleAdapter aiScheduleAdapter;

    public OnboardingController(OnboardingService onboardingService, AiScheduleAdapter aiScheduleAdapter) {
        this.onboardingService = onboardingService;
        this.aiScheduleAdapter = aiScheduleAdapter;
    }

    public record ParseScheduleApiRequest(
            @Schema(description = "근무표 사진, base64 인코딩 문자열 (data URL 접두사 포함해도 무방)")
            @NotBlank String imageBase64,
            @Schema(description = """
            여러 명이 찍힌 근무표에서 본인 행을 특정하기 위한 라벨. 최초 호출 시엔 생략(null).
            + AI가 행을 특정 못하면 422(ROW_LABEL_REQUIRED)와 함께 rowLabels 목록을 반환하는데,
            + 그중 사용자가 고른 값을 담아 같은 이미지로 재호출하면 된다.
            """, nullable = true)
            String myRowLabel
    ) {
    }

    @Operation(
            summary = "근무표 사진 AI 파싱",
            description = """
                    근무표 사진을 AI로 분석해 shiftTypes(근무유형별 시작/종료 시각)와 shifts(날짜별 근무유형)를 반환한다.
                    사진에 여러 명의 행이 있어 AI가 본인 행을 특정하지 못하면 422 { "error": "ROW_LABEL_REQUIRED", "rowLabels": [...] }
                    를 반환하니, 이 경우 rowLabels를 사용자에게 보여주고 고른 값을 myRowLabel에 담아 재호출한다.
                    이 엔드포인트는 파싱 결과만 반환하며 DB에 아무것도 저장하지 않는다 — 사용자가 결과를 검토/수정한 뒤
                    /api/onboarding/schedule로 최종 확정해야 한다.
                    + 온보딩 [근무표 불러오기] 버튼에 연결해 주세요. 
                    근무표 사진에 여러명의 근무자가 포함되어 있을 경우에는
                    최초에 인식된 근무표 화면에서 근무자를 선택하면 myRowLabel에 담고, [근무표 등록하기] 버튼 클릭 시 
                    /api/onboarding/schedule/parse -> /api/onboarding/schedule 순으로 호출되게 연결하시면 됩니다.
                    """
    )
    @PostMapping("/api/onboarding/schedule/parse")
    public ParseScheduleResponse parseSchedule(@Valid @RequestBody ParseScheduleApiRequest request) {
        return aiScheduleAdapter.parseSchedule(new ParseScheduleRequest(request.imageBase64(), request.myRowLabel()))
                .orElseThrow(ParseFailedException::new);
    }

    public record ShiftTypeDefaultDto(
            @NotNull ShiftType shiftType,
            @Schema(description = "이 근무유형의 기본 시작 시각") @NotNull LocalTime startTime,
            @Schema(description = "이 근무유형의 기본 종료 시각") @NotNull LocalTime endTime
    ) {
    }

    public record ProfileRequest(
            @Schema(description = "사용자 이름/닉네임, 최대 20자") @NotBlank @Size(max = 20) String name,
            @Schema(description = "출근 준비시간(분), 15분 단위 입력 권장") int commuteMinutes,
            @Schema(description = "편도 통근시간(분), 15분 단위 입력 권장") int prepMinutes,
            @Schema(description = "개인 목표 수면시간(분), 기본 420(=7시간)") int targetSleepMinutes,
            @Schema(description = "근무 중 낮잠/휴식 가능 여부") boolean napAvailable,
            @Schema(description = "napAvailable=true일 때만 사용하는 가능 시간(분)", nullable = true) Integer napAvailableMinutes,
            @Schema(description = "휴무 시 리듬 선호 경향: RHYTHM_LEAN(리듬유지우선)/BALANCED(균형)/DAY_LEAN(낮생활우선)")
            @NotNull RhythmPreference rhythmPreference,
            @Schema(description = "근무유형(DAY/EVENING/NIGHT)별 기본 시작·종료 시각 목록. 2교대면 DAY/NIGHT만, 3교대면 EVENING까지 포함")
            @NotNull List<ShiftTypeDefaultDto> shiftTypeDefaults
    ) {
    }

    public record ShiftDto(@NotNull LocalDate date, @NotNull ShiftType shiftType) {
    }

    public record ScheduleRequest(
            @Schema(description = """
            날짜별 확정 근무유형 목록. AI 파싱 결과를 사용자가 검토/수정한 최종본을 담아 보낸다.
            + 온보딩 [이 근무표로 나만의 리듬 완성하기] 버튼에 연결해 주세요.
            BE에서 null 값을 받지 않고, default 값도 없기에 모든 값이 필수적으로 요구됩니다.
            """)
            @NotNull List<ShiftDto> shifts
    ) {
    }

    public record OkResponse(boolean ok) {
    }

    @Operation(summary = "개인화 데이터 등록/수정", description = "온보딩 1단계. 이미 등록된 프로필이 있으면 덮어쓴다(단일세션이라 프로필은 항상 1개).")
    @PostMapping("/api/onboarding/profile")
    public OkResponse profile(@Valid @RequestBody ProfileRequest request) {
        List<OnboardingService.ShiftTypeDefaultInput> defaults = request.shiftTypeDefaults().stream()
                .map(d -> new OnboardingService.ShiftTypeDefaultInput(d.shiftType(), d.startTime(), d.endTime()))
                .toList();
        onboardingService.upsertProfile(request.name(), request.commuteMinutes(), request.prepMinutes(), request.targetSleepMinutes(),
                request.napAvailable(), request.napAvailableMinutes(), request.rhythmPreference(), defaults);
        return new OkResponse(true);
    }

    @Operation(
            summary = "근무표 확정 등록",
            description = """
                    온보딩 2단계. /api/onboarding/profile이 먼저 호출돼 있어야 한다(없으면 500).
                    이 호출 하나로 날짜별 규칙 기반 수면/식사 초안 계산 + AI(suggest-adjustment) 개인화 조정까지
                    한 번에 끝나고, 각 날짜의 RoutineResult(version=1)가 생성된다. 이후 /api/routines/today로
                    오늘의 루틴을 조회할 수 있다.
                    + 온보딩 [근무표 등록하기] 버튼에 연결해 주세요. 다만 여러명의 근무자 중 본인 행을 
                    특정해야 하는 경우에는 /api/onboarding/schedule/parse -> /api/onboarding/schedule 순으로 호출되게 연결하시면 됩니다.
                    """
    )
    @PostMapping("/api/onboarding/schedule")
    public OkResponse schedule(@Valid @RequestBody ScheduleRequest request) {
        List<OnboardingService.ShiftInput> shifts = request.shifts().stream()
                .map(s -> new OnboardingService.ShiftInput(s.date(), s.shiftType()))
                .toList();
        onboardingService.registerSchedule(shifts);
        return new OkResponse(true);
    }
}

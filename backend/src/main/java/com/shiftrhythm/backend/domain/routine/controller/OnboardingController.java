package com.shiftrhythm.backend.domain.routine.controller;
import com.shiftrhythm.backend.domain.routine.*;
import com.shiftrhythm.backend.domain.routine.service.*;

import com.shiftrhythm.backend.domain.ai.AiScheduleAdapter;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleRequest;
import com.shiftrhythm.backend.domain.ai.dto.ParseScheduleResponse;
import com.shiftrhythm.backend.domain.routine.service.OnboardingService;
import com.shiftrhythm.backend.domain.routine.ParseFailedException;
import com.shiftrhythm.backend.domain.routine.ScheduleDayView;
import com.shiftrhythm.backend.domain.schedule.RhythmPreference;
import com.shiftrhythm.backend.domain.schedule.ShiftType;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

                    + shifts[].shiftType은 근무표에 적힌 코드 그대로다("D", "나이트", "1조"...). 그 코드가
                    + DAY/EVENING/NIGHT/OFF 중 무엇인지는 shiftTypes[]에서 같은 shiftType을 찾아 mapped를 보면 된다.
                    + 달력에 근무유형을 칠할 때도, /api/onboarding/schedule로 확정할 때도 이 mapped 값을 쓴다
                    + (확정 API는 ShiftType enum만 받는다). confidence가 low면 AI도 확신이 없다는 뜻이라
                    + 확인 화면에서 사용자에게 물어보는 게 좋다.
                    + 근무표에 시간표(범례)가 없으면 startTime/endTime은 AI가 추측하지 않고 교대 프리셋
                    + 기본값이 들어간다(3교대 06-14/14-22/22-06, 2교대 08-20/20-08).
                    사진에 여러 명의 행이 있어 AI가 본인 행을 특정하지 못하면 422 { "error": "ROW_LABEL_REQUIRED", "rowLabels": [...], "rowPreviews": [...] }
                    를 반환하니, 이 경우 rowLabels를 사용자에게 보여주고 고른 값을 myRowLabel에 담아 재호출한다. rowPreviews는
                    rowLabels와 같은 순서·길이의 행별 근무 패턴 요약 문자열(예: "N N OFF OFF N N OFF")이라 이름 옆에 같이
                    보여주면 본인 행을 더 쉽게 찾을 수 있다 — 길이가 안 맞으면 rowPreviews 자체가 응답에서 빠질 수 있으니
                    없는 경우(null)엔 이름만 보여주면 된다.
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
            @Schema(description = "편도 통근시간(분), 15분 단위 입력 권장") int commuteMinutes,
            @Schema(description = "출근 준비시간(분), 15분 단위 입력 권장") int prepMinutes,
            @Schema(description = "개인 목표 수면시간(분), 기본 420(=7시간)") int targetSleepMinutes,
            @Schema(description = "근무 중 낮잠/휴식 가능 여부") boolean napAvailable,
            @Schema(description = "napAvailable=true일 때만 사용하는 가능 시간(분)", nullable = true) Integer napAvailableMinutes,
            @Schema(description = "휴무 시 리듬 선호 경향: RHYTHM_LEAN(리듬유지우선)/BALANCED(균형)/DAY_LEAN(낮생활우선)")
            @NotNull RhythmPreference rhythmPreference
    ) {
    }

    public record ShiftDto(@NotNull LocalDate date, @NotNull ShiftType shiftType) {
    }

    public record ScheduleRequest(
            @Schema(description = "근무유형(DAY/EVENING/NIGHT)별 기본 시작·종료 시각 목록. /schedule/parse가 돌려준 "
                    + "shiftTypes를 사용자가 검토/보정한 최종본을 담아 보낸다. 2교대면 DAY/NIGHT만, 3교대면 EVENING까지 포함.")
            @NotNull List<ShiftTypeDefaultDto> shiftTypeDefaults,
            @Schema(description = """
                    날짜별 확정 근무유형 목록. AI 파싱 결과를 사용자가 검토/수정한 최종본을 담아 보낸다.
                    온보딩 [이 근무표로 나만의 리듬 완성하기] 버튼에 연결해 주세요.
                    BE에서 null 값을 받지 않고, default 값도 없기에 모든 값이 필수적으로 요구됩니다.
                    """)
            @NotNull List<ShiftDto> shifts
    ) {
    }

    public record OkResponse(boolean ok) {
    }

    public record ProfileResponse(boolean ok, Long userId) {
    }

    public record ProfileView(String name, int commuteMinutes, int prepMinutes, int targetSleepMinutes,
                              boolean napAvailable, Integer napAvailableMinutes, RhythmPreference rhythmPreference) {
    }

    @Operation(
            summary = "개인화 데이터 등록/수정",
            description = """
                    온보딩 1단계. 로그인이 없으므로 이 호출이 곧 사용자 발급이다.
                    X-User-Id 헤더 없이 호출하면 새 사용자를 만들고 응답의 userId를 돌려준다 —
                    프론트는 이 값을 localStorage에 저장해 이후 모든 요청에 X-User-Id 헤더로 붙인다.
                    헤더를 달고 호출하면 그 사용자의 프로필을 덮어쓴다(마이페이지 수정).
                    '새로 시작하기'는 저장된 userId를 지우고 헤더 없이 이 API를 다시 호출하는 것이다.
                    """
    )
    @PostMapping("/api/onboarding/profile")
    public ProfileResponse profile(@Valid @RequestBody ProfileRequest request) {
        UserProfile saved = onboardingService.upsertProfile(request.name(), request.commuteMinutes(), request.prepMinutes(),
                request.targetSleepMinutes(), request.napAvailable(), request.napAvailableMinutes(), request.rhythmPreference());
        return new ProfileResponse(true, saved.getId());
    }

    @Operation(
            summary = "프로필 조회",
            description = "마이페이지용. X-User-Id 헤더가 가리키는 프로필을 반환한다. 아직 온보딩 전이면 204(본문 없음)."
    )
    @GetMapping("/api/onboarding/profile")
    public ResponseEntity<ProfileView> getProfile() {
        return onboardingService.findProfile()
                .map(p -> ResponseEntity.ok(new ProfileView(p.getName(), p.getCommuteMinutes(), p.getPrepMinutes(),
                        p.getTargetSleepMinutes(), p.isNapAvailable(), p.getNapAvailableMinutes(), p.getRhythmPreference())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(
            summary = "근무표 확정 등록",
            description = """
                    온보딩 2단계. /api/onboarding/profile이 먼저 호출돼 있어야 한다(없으면 500).
                    shiftTypeDefaults는 /schedule/parse가 돌려준 shiftTypes를 사용자가 검토/보정한 최종본이다
                    (근무유형별 시작/종료 시각은 AI가 사진에서 읽어주는 값이지 사용자가 처음부터 입력하는 값이 아니다).
                    이 호출 하나로 shiftTypeDefaults 저장 + 날짜별 규칙 기반 수면/식사 초안 계산 +
                    AI(suggest-adjustment) 개인화 조정까지 한 번에 끝나고, 각 날짜의 RoutineResult(version=1)가
                    생성된다. 이후 /api/routines/today로 오늘의 루틴을 조회할 수 있다.
                    + 온보딩 [근무표 등록하기] 버튼에 연결해 주세요. 다만 여러명의 근무자 중 본인 행을
                    특정해야 하는 경우에는 /api/onboarding/schedule/parse -> /api/onboarding/schedule 순으로 호출되게 연결하시면 됩니다.
                    """
    )
    @PostMapping("/api/onboarding/schedule")
    public OkResponse schedule(@Valid @RequestBody ScheduleRequest request) {
        List<OnboardingService.ShiftTypeDefaultInput> defaults = request.shiftTypeDefaults().stream()
                .map(d -> new OnboardingService.ShiftTypeDefaultInput(d.shiftType(), d.startTime(), d.endTime()))
                .toList();
        List<OnboardingService.ShiftInput> shifts = request.shifts().stream()
                .map(s -> new OnboardingService.ShiftInput(s.date(), s.shiftType()))
                .toList();
        onboardingService.registerSchedule(defaults, shifts);
        return new OkResponse(true);
    }

    @Operation(
            summary = "등록된 근무표 조회",
            description = """
                    POST /api/onboarding/schedule로 근무표가 확정된 "이후"에만 의미 있는 조회다 — 확정 전
                    (사진 파싱 직후 검토 단계)에는 아직 저장된 게 없어서 빈 목록이 온다.

                    확정된 모든 날짜의 근무유형과 유효 시각(개별 시각 지정이 없으면 근무유형 기본 시각)을 반환한다.
                    재실행 시 온보딩 화면에서 기존값 확인, 캘린더 화면 렌더링, 날짜 클릭 후 수정 폼(PATCH
                    /{date}) 초기값 채우기에 사용하면 된다. hasCustomTime=true인 날짜는 그날만 시각이 따로
                    지정돼 있다는 뜻이다.
                    """
    )
    @GetMapping("/api/onboarding/schedule")
    public List<ScheduleDayView> getSchedule() {
        return onboardingService.getSchedule();
    }

    public record AnchorStartDateRequest(
            @Schema(description = "/schedule/parse가 돌려준(또는 사용자가 화면에서 검토 중인) 날짜별 근무 목록. "
                    + "아직 서버에 저장되지 않은, 확정 전 상태를 그대로 담아 보낸다.")
            @NotNull List<ShiftDto> shifts,
            @Schema(description = "사용자가 고른 새 시작일(오늘을 포함하도록). shifts의 상대적 순서/근무유형은 그대로 두고 이 날짜부터 다시 배치한다.")
            @NotNull LocalDate newStartDate
    ) {
    }

    public record AnchorStartDateResponse(List<ShiftDto> shifts) {
    }

    @Operation(
            summary = "근무표 시작일 보정 (저장 전, 순수 계산)",
            description = """
                    아직 POST /api/onboarding/schedule로 확정 등록하기 "전" 단계에서만 쓴다 — 사진 파싱
                    직후 검토 화면에서, 파싱된 근무표에 오늘이 포함되지 않으면(monthGuessed 여부와 무관하게
                    항상 발생할 수 있다) 사용자가 시작일을 새로 고르는 데 쓴다.

                    shifts의 상대적 패턴(순서, 근무유형)은 그대로 두고 날짜만 newStartDate부터 다시 배치해서
                    돌려준다 — DB에 아무것도 저장하지 않는 순수 계산이다. 프론트는 이 응답의 shifts를 그대로
                    (또는 사용자가 더 검토/수정한 뒤) POST /api/onboarding/schedule에 최종 등록으로 보내면 된다.

                    이미 확정 등록된 근무표를 나중에(홈 화면에서 오늘이 범위를 벗어난 경우) 고치는 건 이
                    엔드포인트가 아니라 PATCH /api/onboarding/schedule/start-date를 쓴다 — 그건 DB에 이미
                    저장된 근무표를 대상으로 하고, 이건 아직 저장되지 않은 걸 대상으로 한다.
                    """
    )
    @PostMapping("/api/onboarding/schedule/anchor-start-date")
    public AnchorStartDateResponse anchorStartDate(@Valid @RequestBody AnchorStartDateRequest request) {
        List<OnboardingService.ShiftInput> shifts = request.shifts().stream()
                .map(s -> new OnboardingService.ShiftInput(s.date(), s.shiftType()))
                .toList();
        List<OnboardingService.ShiftInput> anchored = OnboardingService.anchorShiftsToStartDate(shifts, request.newStartDate());
        List<ShiftDto> result = anchored.stream()
                .map(s -> new ShiftDto(s.date(), s.shiftType()))
                .toList();
        return new AnchorStartDateResponse(result);
    }

    public record ShiftStartDateRequest(
            @Schema(description = "근무표의 새 시작일. 기존 근무표의 상대 패턴(요일 순서, 근무유형)은 그대로 두고 이 날짜부터 다시 배치된다.")
            @NotNull LocalDate newStartDate
    ) {
    }

    @Operation(
            summary = "근무표 시작일 수정",
            description = """
                    이미 등록된 근무표의 요일 패턴/근무유형은 그대로 두고, 절대 날짜만 newStartDate부터
                    다시 배치한다. 근무표를 사진 다시 찍어 올리거나 처음부터 재등록할 필요 없이, "오늘이
                    범위 밖이라 저장이 막힌" 경우(SCHEDULE_MISSING_TODAY) 시작일 하나만 골라 고칠 때 쓴다.
                    monthGuessed 여부와 무관하게 언제든 호출 가능하다.

                    내부적으로 POST /api/onboarding/schedule와 동일한 파이프라인을 타므로, 새 범위에도
                    오늘이 안 들어가면 똑같이 422(SCHEDULE_MISSING_TODAY)로 거부된다. 개별 날짜에 지정했던
                    시각 override는 초기화된다(POST와 동일한 전체 재등록이라서).
                    """
    )
    @PatchMapping("/api/onboarding/schedule/start-date")
    public OkResponse shiftStartDate(@Valid @RequestBody ShiftStartDateRequest request) {
        onboardingService.shiftScheduleStartDate(request.newStartDate());
        return new OkResponse(true);
    }

    public record EditShiftRequest(
            @Schema(description = "드롭다운으로 선택: DAY/EVENING/NIGHT/OFF") @NotNull ShiftType shiftType,
            @Schema(description = "시작 시각. OFF면 무시된다. 생략하면 그 근무유형의 기본 시작 시각을 따른다.", nullable = true)
            LocalTime startTime,
            @Schema(description = "종료 시각. OFF면 무시된다. 생략하면 그 근무유형의 기본 종료 시각을 따른다.", nullable = true)
            LocalTime endTime
    ) {
    }

    @Operation(
            summary = "특정 날짜 근무 수정 (이미 확정된 근무표 대상)",
            description = """
                    이 엔드포인트는 POST /api/onboarding/schedule로 근무표가 최소 한 번 확정된 "이후"에만
                    쓴다 — 확정 전(사진 파싱 직후 검토 단계)에는 아직 아무것도 저장돼 있지 않으므로 이 API를
                    호출할 대상 자체가 없다. 그 단계의 수정은 프론트가 /schedule/parse 응답 배열을 화면에서
                    직접 고쳐서, 최종본을 POST /api/onboarding/schedule 한 번에 담아 보내면 된다(그때 비로소
                    Shift와 RoutineResult가 생성된다).

                    이 PATCH는 그렇게 이미 확정된 근무표를, 온보딩을 마친 뒤 재실행하거나 캘린더에서 날짜
                    하나만 다시 고치고 싶을 때 쓴다. 근무표에 등록돼 있지 않은 날짜면 404(SHIFT_NOT_FOUND)를
                    반환한다 — 새 날짜 추가가 아니라 기존 날짜 수정 전용이다.

                    수정 대상 날짜와 그 전후날의 루틴(RoutineResult)을 규칙 기반으로 다시 계산해서 새 버전으로
                    반영한다(모드 판정이 인접일에 영향을 주기 때문). AI 재호출은 하지 않으며, 이후
                    GET /api/routines/today를 조회할 때 체크인 트리거가 있으면 그때 AI 개인화가 다시 적용된다.
                    """
    )
    @PatchMapping("/api/onboarding/schedule/{date}")
    public OkResponse editShift(
            @Schema(description = "수정할 날짜, ISO-8601 (예: 2026-08-20)")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody EditShiftRequest request) {
        onboardingService.editShift(date, request.shiftType(), request.startTime(), request.endTime());
        return new OkResponse(true);
    }
}

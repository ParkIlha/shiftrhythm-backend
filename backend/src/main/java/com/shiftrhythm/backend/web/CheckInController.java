package com.shiftrhythm.backend.web;

import com.shiftrhythm.backend.domain.checkin.CheckInService;
import com.shiftrhythm.backend.domain.checkin.entity.DailyCheckIn;
import com.shiftrhythm.backend.domain.schedule.ShiftType;
import com.shiftrhythm.backend.domain.schedule.entity.ShiftTypeDefault;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import com.shiftrhythm.backend.domain.schedule.repository.ShiftRepository;
import com.shiftrhythm.backend.domain.schedule.repository.ShiftTypeDefaultRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;

@RestController
@Tag(name = "체크인", description = """
        기상/퇴근 시점에 사용자가 입력하는 짧은 카드형 체크인. 무응답이어도 각 도메인은 기본값으로 폴백 동작한다.
        메인 페이지 하단에 카드 형태로 노출되는 기능이며, 팝업이 아니라 무시 가능한 카드다 — 무응답 시 사라지지 않고
        배지로 축소되며 언제든 다시 입력할 수 있다.
        """)
public class CheckInController {

    private final CheckInService checkInService;
    private final ShiftRepository shiftRepository;
    private final ShiftTypeDefaultRepository shiftTypeDefaultRepository;

    public CheckInController(CheckInService checkInService, ShiftRepository shiftRepository,
                             ShiftTypeDefaultRepository shiftTypeDefaultRepository) {
        this.checkInService = checkInService;
        this.shiftRepository = shiftRepository;
        this.shiftTypeDefaultRepository = shiftTypeDefaultRepository;
    }

    public record WakeRequest(
            @NotNull LocalDate date,
            @Schema(description = "기상 시 컨디션, 1~5점", nullable = true) Integer conditionScore,
            @Schema(description = "수면 만족도, 1~5점", nullable = true) Integer sleepSatisfaction,
            @Schema(description = "잠드는데 걸린 시간(분)", nullable = true) Integer sleepLatencyMinutes
    ) {
    }

    public record WakeResponse(boolean ok, LocalDate date) {
    }

    public record ClockOutRequest(
            @NotNull LocalDate date,
            @Schema(description = "실제 퇴근 시각. 모든 근무유형 공통으로 입력받는다.")
            @NotNull LocalTime actualClockOut,
            @Schema(description = "퇴근 후 허기 정도, 1~5점. NIGHT/EVENING 근무일에만 프론트가 입력 UI를 노출하는 걸 전제로 optional.", nullable = true)
            Integer nightHungerScore
    ) {
    }

    public record ClockOutResponse(
            boolean ok, LocalDate date,
            @Schema(description = "그날 예정돼 있던 근무 종료 시각. 등록된 근무가 없으면 null.", nullable = true) LocalTime scheduledClockOut,
            @Schema(description = "예정 대비 실제 퇴근까지 걸린 지연 시간(분). 지연이면 양수. scheduledClockOut이 null이면 항상 0.")
            long delayMinutes
    ) {
    }

    @Operation(
            summary = "기상 체크인",
            description = """
                    [표출 시점 판단 방법]
                    1. GET /api/routines/today를 호출한다.
                    2. 응답의 timeline 배열에서 type=="주수면"인 세그먼트를 찾아 그 end(LocalTime)를 구한다.
                    3. 현재 시각이 그 end 이후이고, 오늘 이 카드를 아직 닫지 않았다면(=오늘 wake 체크인을 아직
                       안 보냈다면) 카드를 노출한다.
                    근무유형(mode)과 무관하게 매일 노출 대상이다 — mode 값은 이 판단에 쓰지 않는다.

                    "오늘 이미 닫았는지" 상태는 백엔드가 들고 있지 않으므로(무응답이어도 배지로 축소되어
                    언제든 재입력 가능해야 하는 요구사항 때문) 프론트가 로컬로 관리해야 한다. 자정이 지나면
                    (날짜가 바뀌면) 이 로컬 상태를 초기화해서 다음 날 다시 노출되게 할 것.

                    이미 그날 체크인이 있으면 필드별로 부분 업데이트한다 — null로 보낸 필드는 기존 값이
                    그대로 유지되고, 값을 채워 보낸 필드만 덮어쓴다.
                    """
    )
    @PostMapping("/api/checkins/wake")
    public WakeResponse wake(@RequestBody WakeRequest request) {
        checkInService.wake(request.date(), request.conditionScore(), request.sleepSatisfaction(), request.sleepLatencyMinutes());
        return new WakeResponse(true, request.date());
    }

    @Operation(
            summary = "퇴근 체크인",
            description = """
                    [표출 시점 판단 방법]
                    1. GET /api/routines/today를 호출한다.
                    2. 응답의 mode가 "NIGHT" 또는 "EVENING"이 아니면 이 카드는 이날 아예 노출 대상이 아니다
                       (DAY/SHIFT_TRANSITION/OFF_* 등에서는 절대 뜨지 않는다).
                    3. mode가 NIGHT/EVENING이면, timeline 배열에서 type=="근무"인 세그먼트의 end(LocalTime)를
                       구한다. 현재 시각이 그 end 이후이고 오늘 이 카드를 아직 닫지 않았다면 카드를 노출한다.

                    "오늘 이미 닫았는지" 상태는 기상 체크인과 마찬가지로 백엔드가 들고 있지 않으므로 프론트가
                    로컬로 관리해야 하고, 자정이 지나면 초기화한다.

                    이 체크인이 저장되면 다음 GET /api/routines/today 조회 시 AI가 오늘 상황을 반영해 루틴을
                    재조정하도록 트리거된다(RoutineFacade의 AI 재호출 판단 기준: 체크인의 updatedAt이 루틴의
                    aiUpdatedAt보다 최신이면 재호출).
                    """
    )
    @PostMapping("/api/checkins/clockout")
    public ClockOutResponse clockout(@RequestBody ClockOutRequest request) {
        DailyCheckIn saved = checkInService.clockout(request.date(), request.actualClockOut(), request.nightHungerScore());

        // 그날만 시각을 따로 지정한 경우가 아니면 근무유형 기본 종료시각이 예정 퇴근시각이다
        // (override만 보면 평범한 날은 늘 null이라 지연이 0분으로 잡힌다).
        LocalTime scheduledClockOut = shiftRepository
                .findByUserProfileIdAndDate(CurrentUser.id(), request.date())
                .filter(s -> s.getShiftType() != ShiftType.OFF)
                .map(s -> s.getEndTimeOverride() != null
                        ? s.getEndTimeOverride()
                        : shiftTypeDefaultRepository
                                .findByUserProfileIdAndShiftType(CurrentUser.id(), s.getShiftType())
                                .map(ShiftTypeDefault::getDefaultEndTime)
                                .orElse(null))
                .orElse(null);
        long delayMinutes = 0;
        if (scheduledClockOut != null) {
            delayMinutes = java.time.Duration.between(scheduledClockOut, saved.getActualClockOut()).toMinutes();
        }
        return new ClockOutResponse(true, request.date(), scheduledClockOut, delayMinutes);
    }
}

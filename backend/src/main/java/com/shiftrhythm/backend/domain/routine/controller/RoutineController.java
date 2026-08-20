package com.shiftrhythm.backend.domain.routine.controller;
import com.shiftrhythm.backend.domain.routine.*;
import com.shiftrhythm.backend.domain.routine.service.*;

import com.shiftrhythm.backend.domain.routine.service.ReplanFacade;
import com.shiftrhythm.backend.domain.routine.service.RoutineFacade;
import com.shiftrhythm.backend.domain.routine.TodayRoutineView;
import com.shiftrhythm.backend.domain.schedule.util.AppClock;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Tag(name = "루틴/재설계", description = "오늘의 루틴 조회 및 자유텍스트 기반 재설계(계획 변경) API.")
public class RoutineController {

    private final RoutineFacade routineFacade;
    private final ReplanFacade replanFacade;

    public RoutineController(RoutineFacade routineFacade, ReplanFacade replanFacade) {
        this.routineFacade = routineFacade;
        this.replanFacade = replanFacade;
    }

    public record ReplanPreviewRequest(
            @Schema(description = "재설계 사유를 적은 자유텍스트. 예: \"오늘 회식 때문에 퇴근이 2시간 늦어질 것 같아요\". "
                    + "AI(parse-disruption)가 이걸 구조화된 이벤트로 변환해 재계산한다.")
            @NotBlank String rawText
    ) {
    }

    public record ReplanConfirmRequest(
            @Schema(description = "preview 응답으로 받은 previewId. preview는 DB에 저장되지 않고 5분 TTL 인메모리 캐시에만 있으므로, "
                    + "5분 안에 confirm하지 않으면 410(PREVIEW_EXPIRED)이 발생하며 다시 preview부터 호출해야 한다.")
            @NotNull UUID previewId
    ) {
    }

    @Operation(
            summary = "오늘의 루틴 조회",
            description = """
                    현재 시각이 속한 근무 사이클의 날짜를 판정한 뒤 그날의 루틴(모드, 타임라인, 식사 제약, 근거)을 반환한다.
                    자정~06시 사이이고 어제가 야간(NIGHT류) 사이클이었다면 어제 날짜를 오늘 사이클로 취급한다.
                    체크인 이후 처음 조회되는 시점이면 내부적으로 AI(suggest-adjustment)를 재호출해 개인화를 시도하고,
                    이때 실제로 반영됐으면 wasJustPersonalized=true가 내려온다. 해당 날짜에 등록된 루틴이 없으면
                    404(ROUTINE_NOT_FOUND)를 반환한다.
                    """
    )
    @GetMapping("/api/routines/today")
    public TodayRoutineView today() {
        var date = routineFacade.resolveCurrentCycleDate(AppClock.now());
        return routineFacade.getToday(date);
    }

    @Operation(
            summary = "재설계 요청/미리보기",
            description = """
                    자유텍스트를 AI로 파싱해 규칙+AI 기준으로 재계산한 전/후 비교(before/after)를 반환한다.
                    이 단계에서는 아직 DB에 반영되지 않는다 — 사용자가 결과를 보고 confirm(/replan/confirm)을
                    호출해야 실제로 확정(RoutineResult 새 version 생성)된다. previewId는 5분간만 유효하다.
                    + 해당 계획 블럭을 누르면 드롭다운 형식으로 텍스트 입력 창을 열고 계획 변경 사유를 rawText로 보낼 수 
                    있도록 해주세요. 그리고 응답 값을 받아 해당 계획 블럭 아래 이전 계획(before)과 재계산된 계획(after)을 비교해서 보여주고, 
                    사용자가 [이대로 확정] 버튼을 누르면 previewId를 담아 /api/routines/replan/confirm로 POST 요청을 보내도록 연결해 주세요.
                    """
    )
    @PostMapping("/api/routines/replan/preview")
    public ReplanFacade.PreviewResult preview(@RequestBody ReplanPreviewRequest request) {
        return replanFacade.preview(request.rawText());
    }

    @Operation(
            summary = "재설계 확정",
            description = """
            preview에서 받은 previewId로 재설계를 확정한다. 기존 is_current 행을 false로 바꾸고
            새 version의 RoutineResult를 is_current=true로 생성한다(append-only, 되돌리기는 이전 버전을 그대로 두는 방식).
            + [이대로 확정] 버튼에 연결해주시면 됩니다.
            """
    )
    @PostMapping("/api/routines/replan/confirm")
    public ReplanFacade.ConfirmResult confirm(@RequestBody ReplanConfirmRequest request) {
        return replanFacade.confirm(request.previewId());
    }
}

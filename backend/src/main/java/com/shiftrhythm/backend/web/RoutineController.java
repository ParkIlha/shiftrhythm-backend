package com.shiftrhythm.backend.web;

import com.shiftrhythm.backend.domain.routine.ReplanFacade;
import com.shiftrhythm.backend.domain.routine.RoutineFacade;
import com.shiftrhythm.backend.domain.routine.TodayRoutineView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
public class RoutineController {

    private final RoutineFacade routineFacade;
    private final ReplanFacade replanFacade;

    public RoutineController(RoutineFacade routineFacade, ReplanFacade replanFacade) {
        this.routineFacade = routineFacade;
        this.replanFacade = replanFacade;
    }

    public record ReplanPreviewRequest(@NotBlank String rawText) {
    }

    public record ReplanConfirmRequest(@NotNull UUID previewId) {
    }

    @GetMapping("/api/routines/today")
    public TodayRoutineView today() {
        var date = routineFacade.resolveCurrentCycleDate(LocalDateTime.now());
        return routineFacade.getToday(date);
    }

    @PostMapping("/api/routines/replan/preview")
    public ReplanFacade.PreviewResult preview(@RequestBody ReplanPreviewRequest request) {
        return replanFacade.preview(request.rawText());
    }

    @PostMapping("/api/routines/replan/confirm")
    public ReplanFacade.ConfirmResult confirm(@RequestBody ReplanConfirmRequest request) {
        return replanFacade.confirm(request.previewId());
    }
}

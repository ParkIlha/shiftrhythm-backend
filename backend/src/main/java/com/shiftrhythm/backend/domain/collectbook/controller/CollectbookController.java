package com.shiftrhythm.backend.domain.collectbook.controller;
import com.shiftrhythm.backend.domain.collectbook.*;
import com.shiftrhythm.backend.domain.collectbook.service.*;

import com.shiftrhythm.backend.domain.collectbook.service.CollectbookFacade;
import com.shiftrhythm.backend.domain.collectbook.CollectbookView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

@RestController
@Tag(name = "콜렉트북", description = "기상시각을 시차(zone)로 은유해 이번 달/과거 달에 어떤 시간대에서 살았는지 보여주는 API. "
        + "별도 저장 없이 RoutineResult만 스캔해서 매번 계산한다.")
public class CollectbookController {

    private final CollectbookFacade collectbookFacade;

    public CollectbookController(CollectbookFacade collectbookFacade) {
        this.collectbookFacade = collectbookFacade;
    }

    @Operation(
            summary = "콜렉트북 월별 조회",
            description = """
                    해당 월의 요약 문구, 시간대 스택(생활일수 DESC 정렬, 동률이면 최근에 산 시간대 우선),
                    월간 총 시차이동시간과 가장 큰 하루 이동시간을 반환한다.
                    각 카드의 isNew는 그 시간대(zone)가 이 프로필 역사상 이번 달에 처음 나타났는지를
                    의미한다 — "다음 진입부터 제거"는 프론트가 로컬 상태로 관리해야 한다(백엔드는 상태를
                    들고 있지 않음).
                    미래 월로의 이동 제한은 프론트에서 처리하며 백엔드는 별도 검증하지 않는다.
                    """
    )
    @GetMapping("/api/collectbook")
    public CollectbookView collectbook(
            @Parameter(description = "조회할 월, YYYY-MM 형식 (예: 2026-08)", example = "2026-08")
            @RequestParam String month) {
        return collectbookFacade.get(YearMonth.parse(month));
    }
}

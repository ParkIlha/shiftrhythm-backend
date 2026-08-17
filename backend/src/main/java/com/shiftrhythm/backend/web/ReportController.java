package com.shiftrhythm.backend.web;

import com.shiftrhythm.backend.domain.report.DailyReportView;
import com.shiftrhythm.backend.domain.report.MonthlyReportView;
import com.shiftrhythm.backend.domain.report.ReportFacade;
import com.shiftrhythm.backend.domain.report.WeeklyReportView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;

@RestController
@Tag(name = "리포트", description = "수면/재계획 기록을 주간·월간·일별로 집계해서 보여주는 기록 분석 리포트 API. 별도로 저장되는 값 없이 RoutineResult/DailyCheckIn만으로 매 요청마다 계산한다.")
public class ReportController {

    private final ReportFacade reportFacade;

    public ReportController(ReportFacade reportFacade) {
        this.reportFacade = reportFacade;
    }

    @Operation(
            summary = "주간 리포트",
            description = """
                    from~to 범위(경계 포함)의 총/일평균 수면시간, 사유별 재계획 집계, 날짜별 요약 리스트를 반환한다.
                    범위는 프론트가 계산해서 넘긴다(스테퍼 ±7일, 미래 이동 금지 등은 프론트에서 제어하며 백엔드는 별도 검증을 하지 않는다).
                    데이터가 없는 날짜(RoutineResult가 없는 날)는 days 리스트에서 그냥 빠진다.
                    """
    )
    @GetMapping("/api/reports/weekly")
    public WeeklyReportView weekly(
            @Parameter(description = "조회 시작일(포함), ISO-8601 (예: 2026-08-03)", example = "2026-08-03")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일(포함), ISO-8601 (예: 2026-08-09)", example = "2026-08-09")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportFacade.weekly(from, to);
    }

    @Operation(
            summary = "월간 리포트",
            description = """
                    해당 월의 총/일평균 수면시간, 전월 대비 평균(전월에 데이터가 없으면 averageSleepMinutesPrevMonth는 null),
                    재계획 일수와 사유별 집계를 반환한다.
                    """
    )
    @GetMapping("/api/reports/monthly")
    public MonthlyReportView monthly(
            @Parameter(description = "조회할 월, YYYY-MM 형식 (예: 2026-08)", example = "2026-08")
            @RequestParam String month) {
        return reportFacade.monthly(YearMonth.parse(month));
    }

    @Operation(
            summary = "일별 상세 리포트",
            description = """
                    특정 날짜의 계획(version=1, 최초 규칙 기반 초안) vs 실제(현재 is_current로 남아있는 최종 확정본) 수면을 비교하고,
                    그날 발생한 재계획 이력(버전별 사유·AI 근거·변경 전후 시각)과 체크인 기록(없으면 checkIn은 null)을 함께 반환한다.
                    해당 날짜에 등록된 루틴이 없으면 404(ROUTINE_NOT_FOUND)를 반환한다.
                    """
    )
    @GetMapping("/api/reports/daily")
    public DailyReportView daily(
            @Parameter(description = "조회할 날짜, ISO-8601 (예: 2026-08-05)", example = "2026-08-05")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reportFacade.daily(date);
    }
}

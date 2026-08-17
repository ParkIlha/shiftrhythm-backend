package com.shiftrhythm.backend.web;

import com.shiftrhythm.backend.domain.report.DailyReportView;
import com.shiftrhythm.backend.domain.report.MonthlyReportView;
import com.shiftrhythm.backend.domain.report.ReportFacade;
import com.shiftrhythm.backend.domain.report.WeeklyReportView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;

@RestController
public class ReportController {

    private final ReportFacade reportFacade;

    public ReportController(ReportFacade reportFacade) {
        this.reportFacade = reportFacade;
    }

    @GetMapping("/api/reports/weekly")
    public WeeklyReportView weekly(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportFacade.weekly(from, to);
    }

    @GetMapping("/api/reports/monthly")
    public MonthlyReportView monthly(@RequestParam String month) {
        return reportFacade.monthly(YearMonth.parse(month));
    }

    @GetMapping("/api/reports/daily")
    public DailyReportView daily(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reportFacade.daily(date);
    }
}

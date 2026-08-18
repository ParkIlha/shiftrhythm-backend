package com.shiftrhythm.backend.domain.ai.dto;

import java.util.List;

/**
 * @param monthGuessed 요청에 month가 없고 AI도 모든 shift에서 월을 못 읽어서, ai-server가 shifts[].date의
 *                      월을 전부 오늘 날짜 기준으로 추측한 경우 true. 이땐 date의 절대 연/월을 믿지 말고
 *                      day 순서(상대 순번)만 신뢰해야 한다 — 프론트가 사용자에게 시작일을 물어서 그 차이만큼
 *                      전체 shifts를 offset하는 식으로 보정하는 데 쓴다.
 */
public record ParseScheduleResponse(List<ShiftTypeDef> shiftTypes, List<ShiftDay> shifts, boolean monthGuessed) {

    /**
     * shiftType은 근무표에 적힌 코드 그대로("D", "나이트", "1조"...)라 그것만으로는 DAY/EVENING/NIGHT를
     * 알 수 없다. mapped가 그 코드의 의미이고, shifts[].shiftType을 이 표로 조회해서 근무유형을 정한다.
     * 이 두 필드를 안 담고 있어서 프론트 달력이 전부 주간으로 표시됐다(AI는 제대로 분류하고 있었다).
     *
     * @param mapped     DAY | EVENING | NIGHT | OFF | UNKNOWN
     * @param confidence high | medium | low — low면 확인 화면에서 사용자에게 물어야 한다
     */
    public record ShiftTypeDef(String shiftType, String mapped, String confidence,
                               String startTime, String endTime) {
    }

    public record ShiftDay(String date, String shiftType) {
    }
}

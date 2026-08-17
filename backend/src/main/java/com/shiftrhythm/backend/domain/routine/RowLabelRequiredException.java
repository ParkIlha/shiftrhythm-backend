package com.shiftrhythm.backend.domain.routine;

import java.util.List;

/**
 * 근무표 사진에 여러 명의 행이 찍혀 있어 AI가 사용자 본인의 행을 특정하지 못한 경우.
 * AI 서버가 422 ROW_LABEL_REQUIRED와 함께 읽어낸 행 라벨 목록을 반환하면 여기 담아 전달하고,
 * 프론트가 사용자에게 고르게 한 뒤 myRowLabel을 담아 재요청하도록 유도한다.
 */
public class RowLabelRequiredException extends RuntimeException {

    private final List<String> rowLabels;

    public RowLabelRequiredException(List<String> rowLabels) {
        super("ROW_LABEL_REQUIRED");
        this.rowLabels = rowLabels;
    }

    public List<String> rowLabels() {
        return rowLabels;
    }
}

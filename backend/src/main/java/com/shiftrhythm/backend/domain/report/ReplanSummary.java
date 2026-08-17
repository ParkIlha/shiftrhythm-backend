package com.shiftrhythm.backend.domain.report;

import com.shiftrhythm.backend.domain.routine.ReplanReason;

/**
 * 사유별 재계획 집계. keptCount는 그 사유로 재계획된 행 중 이후 추가 수정 없이
 * is_current로 남아있는(=지켜진) 개수.
 */
public record ReplanSummary(ReplanReason reason, int totalCount, int keptCount) {
}

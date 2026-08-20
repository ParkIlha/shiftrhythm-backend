package com.shiftrhythm.backend.domain.collectbook;

import com.shiftrhythm.backend.domain.routine.MealTimes;
import com.shiftrhythm.backend.domain.routine.entity.RoutineResult;
import com.shiftrhythm.backend.domain.routine.repository.RoutineResultRepository;
import com.shiftrhythm.backend.domain.schedule.RoutineMode;
import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CollectbookFacadeTest {

    @Autowired
    private CollectbookFacade collectbookFacade;
    @Autowired
    private RoutineResultRepository routineResultRepository;

    private static MealTimes mealTimes() {
        return new MealTimes(LocalTime.of(7, 0), null, null,
                LocalTime.of(21, 0), LocalTime.of(0, 0), LocalTime.of(6, 0), LocalTime.of(18, 0));
    }

    private void save(LocalDate date, LocalTime wakeTime) {
        LocalDateTime wake = LocalDateTime.of(date, wakeTime);
        routineResultRepository.save(new RoutineResult(UserProfile.SINGLETON_ID, date, 1, true, null, RoutineMode.DAY,
                wake.minusHours(7), wake, null, null, null, mealTimes()));
    }

    @Test
    void zonesAreRankedByLivedDaysDescending() {
        YearMonth month = YearMonth.of(2020, 3); // 과거 월 고정(YearMonth.now()와 절대 안 겹침)
        LocalDate d1 = month.atDay(1);

        save(d1, LocalTime.of(7, 0));                // 한국(+9) 1일
        save(d1.plusDays(1), LocalTime.of(22, 0));    // 코스타리카(-6) 1일차
        save(d1.plusDays(2), LocalTime.of(22, 0));    // 코스타리카(-6) 2일차
        save(d1.plusDays(3), LocalTime.of(22, 0));    // 코스타리카(-6) 3일차

        CollectbookView view = collectbookFacade.get(month);

        assertThat(view.zones()).hasSize(2);
        assertThat(view.zones().get(0).country()).isEqualTo("코스타리카");
        assertThat(view.zones().get(0).livedDays()).isEqualTo(3);
        assertThat(view.zones().get(0).rank()).isEqualTo(1);
        assertThat(view.zones().get(1).country()).isEqualTo("한국");
        assertThat(view.zones().get(1).rank()).isEqualTo(2);
    }

    @Test
    void tieIsBrokenByMostRecentlyLivedZone() {
        YearMonth month = YearMonth.of(2020, 4);
        LocalDate d1 = month.atDay(1);

        save(d1, LocalTime.of(7, 0));               // 한국, 오래된 날
        save(d1.plusDays(5), LocalTime.of(22, 0));   // 코스타리카, 더 최근

        CollectbookView view = collectbookFacade.get(month);

        assertThat(view.zones()).hasSize(2);
        // 둘 다 1일씩 -> 더 최근에 산(코스타리카) 쪽이 1등
        assertThat(view.zones().get(0).country()).isEqualTo("코스타리카");
    }

    @Test
    void representativeSleepIsModeAfterRoundingTo15Minutes() {
        // 세 기상시각 모두 같은 zone(offset -6, 코스타리카) 안에 들어오도록 21:30~22:30 범위에서 고른다.
        YearMonth month = YearMonth.of(2020, 5);
        LocalDate d1 = month.atDay(1);

        save(d1, LocalTime.of(22, 2));               // -> 22:00
        save(d1.plusDays(1), LocalTime.of(21, 58));   // -> 22:00
        save(d1.plusDays(2), LocalTime.of(22, 20));   // -> 22:15

        CollectbookView view = collectbookFacade.get(month);

        assertThat(view.zones()).hasSize(1);
        assertThat(view.zones().get(0).country()).isEqualTo("코스타리카");
        assertThat(view.zones().get(0).representativeSleepEnd()).isEqualTo(LocalTime.of(22, 0));
    }

    @Test
    void zoneIsNewOnlyWhenNeverSeenBeforeThisMonth() {
        YearMonth prevMonth = YearMonth.of(2020, 5);
        YearMonth thisMonth = YearMonth.of(2020, 6);

        save(prevMonth.atDay(1), LocalTime.of(7, 0)); // 한국, 지난달에 이미 경험
        save(thisMonth.atDay(1), LocalTime.of(7, 0)); // 한국, 이번달도 -> NEW 아님
        save(thisMonth.atDay(2), LocalTime.of(22, 0)); // 코스타리카, 처음 등장 -> NEW

        CollectbookView view = collectbookFacade.get(thisMonth);

        ZoneCard korea = view.zones().stream().filter(z -> z.country().equals("한국")).findFirst().orElseThrow();
        ZoneCard costaRica = view.zones().stream().filter(z -> z.country().equals("코스타리카")).findFirst().orElseThrow();
        assertThat(korea.isNew()).isFalse();
        assertThat(costaRica.isNew()).isTrue();
    }

    @Test
    void summary_forPastMonth_doesNotMentionTopZone() {
        YearMonth month = YearMonth.of(2020, 7);
        save(month.atDay(1), LocalTime.of(7, 0));
        save(month.atDay(2), LocalTime.of(22, 0));

        CollectbookView view = collectbookFacade.get(month);

        assertThat(view.summary()).contains("7월에는").contains("2개의 시간대");
        assertThat(view.summary()).doesNotContain("현재");
    }

    @Test
    void summary_forCurrentMonth_mentionsTopZoneAndDays() {
        YearMonth month = YearMonth.now();
        save(month.atDay(1), LocalTime.of(7, 0));
        save(month.atDay(2), LocalTime.of(7, 0));

        CollectbookView view = collectbookFacade.get(month);

        assertThat(view.summary()).contains("현재").contains("한국").contains("2일");
    }

    @Test
    void emptyMonth_hasNoRecordsSummary() {
        CollectbookView view = collectbookFacade.get(YearMonth.of(2019, 1));

        assertThat(view.zones()).isEmpty();
        assertThat(view.summary()).contains("기록이 없어요");
    }

    @Test
    void monthlyTravel_countsFirstDayDeltaFromPreviousMonth() {
        YearMonth prevMonth = YearMonth.of(2020, 8);
        YearMonth thisMonth = YearMonth.of(2020, 9);

        save(prevMonth.atEndOfMonth(), LocalTime.of(7, 0));   // offset 9
        save(thisMonth.atDay(1), LocalTime.of(22, 0));         // offset -6 -> 원형 거리 min(15, 9) = 9

        CollectbookView view = collectbookFacade.get(thisMonth);

        assertThat(view.totalTravelHours()).isEqualTo(9);
        assertThat(view.maxDailyTravelHours()).isEqualTo(9);
    }
}

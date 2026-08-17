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
        routineResultRepository.save(new RoutineResult(UserProfile.SINGLETON_ID, date, 1, true, null, RoutineMode.DAY,
                wakeTime.minusHours(7), wakeTime, null, null, null, mealTimes()));
    }

    @Test
    void zonesAreRankedByLivedDaysDescending() {
        YearMonth month = YearMonth.of(2020, 3); // 과거 월 고정(YearMonth.now()와 절대 안 겹침)
        LocalDate d1 = month.atDay(1);

        save(d1, LocalTime.of(7, 0));                // Seoul(+9) 1일
        save(d1.plusDays(1), LocalTime.of(22, 0));    // Chicago(-6) 1일차
        save(d1.plusDays(2), LocalTime.of(22, 0));    // Chicago(-6) 2일차
        save(d1.plusDays(3), LocalTime.of(22, 0));    // Chicago(-6) 3일차

        CollectbookView view = collectbookFacade.get(month);

        assertThat(view.zones()).hasSize(2);
        assertThat(view.zones().get(0).city()).isEqualTo("Chicago");
        assertThat(view.zones().get(0).livedDays()).isEqualTo(3);
        assertThat(view.zones().get(0).rank()).isEqualTo(1);
        assertThat(view.zones().get(1).city()).isEqualTo("Seoul");
        assertThat(view.zones().get(1).rank()).isEqualTo(2);
    }

    @Test
    void tieIsBrokenByMostRecentlyLivedZone() {
        YearMonth month = YearMonth.of(2020, 4);
        LocalDate d1 = month.atDay(1);

        save(d1, LocalTime.of(7, 0));               // Seoul, 오래된 날
        save(d1.plusDays(5), LocalTime.of(22, 0));   // Chicago, 더 최근

        CollectbookView view = collectbookFacade.get(month);

        assertThat(view.zones()).hasSize(2);
        // 둘 다 1일씩 -> 더 최근에 산(Chicago) 쪽이 1등
        assertThat(view.zones().get(0).city()).isEqualTo("Chicago");
    }

    @Test
    void representativeSleepIsModeAfterRoundingTo15Minutes() {
        YearMonth month = YearMonth.of(2020, 5);
        LocalDate d1 = month.atDay(1);

        save(d1, LocalTime.of(23, 2));               // -> 23:00
        save(d1.plusDays(1), LocalTime.of(22, 58));   // -> 23:00
        save(d1.plusDays(2), LocalTime.of(23, 41));   // -> 23:45

        CollectbookView view = collectbookFacade.get(month);

        assertThat(view.zones()).hasSize(1);
        assertThat(view.zones().get(0).representativeSleepEnd()).isEqualTo(LocalTime.of(23, 0));
    }

    @Test
    void zoneIsNewOnlyWhenNeverSeenBeforeThisMonth() {
        YearMonth prevMonth = YearMonth.of(2020, 5);
        YearMonth thisMonth = YearMonth.of(2020, 6);

        save(prevMonth.atDay(1), LocalTime.of(7, 0)); // Seoul, 지난달에 이미 경험
        save(thisMonth.atDay(1), LocalTime.of(7, 0)); // Seoul, 이번달도 -> NEW 아님
        save(thisMonth.atDay(2), LocalTime.of(22, 0)); // Chicago, 처음 등장 -> NEW

        CollectbookView view = collectbookFacade.get(thisMonth);

        ZoneCard seoul = view.zones().stream().filter(z -> z.city().equals("Seoul")).findFirst().orElseThrow();
        ZoneCard chicago = view.zones().stream().filter(z -> z.city().equals("Chicago")).findFirst().orElseThrow();
        assertThat(seoul.isNew()).isFalse();
        assertThat(chicago.isNew()).isTrue();
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

        assertThat(view.summary()).contains("현재").contains("Seoul").contains("2일");
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
        save(thisMonth.atDay(1), LocalTime.of(22, 0));         // offset -6 -> |(-6)-9|=15

        CollectbookView view = collectbookFacade.get(thisMonth);

        assertThat(view.totalTravelHours()).isEqualTo(15);
        assertThat(view.maxDailyTravelHours()).isEqualTo(15);
    }
}

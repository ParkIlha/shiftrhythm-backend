package com.shiftrhythm.backend.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 온보딩(프로필 등록 → 근무표 등록) → 오늘의 루틴 조회까지의 API 흐름 검증.
 * AI 서버 없이도 검증 가능하도록 FakeAiScheduleAdapter를 사용하는 fakeai 프로필로 구동한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("fakeai")
class OnboardingRoutineFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void onboardingThenTodayRoutine() throws Exception {
        String profileJson = """
                {
                  "name": "테스터",
                  "commuteMinutes": 30,
                  "prepMinutes": 30,
                  "targetSleepMinutes": 420,
                  "napAvailable": false,
                  "napAvailableMinutes": null,
                  "rhythmPreference": "BALANCED"
                }
                """;

        mockMvc.perform(post("/api/onboarding/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        LocalDate today = LocalDate.now();
        String scheduleJson = """
                {
                  "shiftTypeDefaults": [
                    {"shiftType": "DAY", "startTime": "09:00", "endTime": "18:00"},
                    {"shiftType": "EVENING", "startTime": "14:00", "endTime": "22:00"},
                    {"shiftType": "NIGHT", "startTime": "22:00", "endTime": "07:00"}
                  ],
                  "shifts": [
                    {"date": "%s", "shiftType": "DAY"},
                    {"date": "%s", "shiftType": "DAY"}
                  ]
                }
                """.formatted(today, today.plusDays(1));

        mockMvc.perform(post("/api/onboarding/schedule")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/routines/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").exists());
    }

    @Test
    void checkinWakeAndClockoutFlow() throws Exception {
        LocalDate date = LocalDate.now();

        mockMvc.perform(post("/api/checkins/wake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "%s", "conditionScore": 4, "sleepSatisfaction": 3, "sleepLatencyMinutes": 15}
                                """.formatted(date)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(post("/api/checkins/clockout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "%s", "actualClockOut": "%s", "nightHungerScore": 2}
                                """.formatted(date, LocalTime.of(18, 30))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }
}

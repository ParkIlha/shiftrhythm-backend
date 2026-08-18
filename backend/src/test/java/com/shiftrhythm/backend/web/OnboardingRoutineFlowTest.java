package com.shiftrhythm.backend.web;

import com.jayway.jsonpath.JsonPath;
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

        // 헤더 없이 등록 → 새 사용자 발급. 이후 요청은 응답으로 받은 userId를 X-User-Id로 달고 간다.
        String userId = JsonPath.read(
                mockMvc.perform(post("/api/onboarding/profile")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(profileJson))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.ok").value(true))
                        .andExpect(jsonPath("$.userId").isNumber())
                        .andReturn().getResponse().getContentAsString(),
                "$.userId").toString();

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
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/routines/today").header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").exists());

        mockMvc.perform(get("/api/onboarding/profile").header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("테스터"));

        // 그날 개별 시각 지정이 없어도 근무유형 기본 종료시각(18:00)을 예정 퇴근으로 보고 지연을 잡아야 한다.
        mockMvc.perform(post("/api/checkins/clockout")
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"date": "%s", "actualClockOut": "19:30"}
                                """.formatted(today)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduledClockOut").value("18:00:00"))
                .andExpect(jsonPath("$.delayMinutes").value(90));
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

package com.shiftrhythm.backend.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 근무표 사진 파싱 엔드포인트(/api/onboarding/schedule/parse) 검증. FakeAiScheduleAdapter는
 * myRowLabel 유무와 무관하게 고정 응답을 주므로, 이 테스트는 요청/응답 배선(컨트롤러 -> 어댑터
 * -> 응답 직렬화)이 끊어지지 않았는지를 확인하는 용도다. 422 ROW_LABEL_REQUIRED 분기는
 * AiScheduleAdapterImpl이 실제 HTTP 에러 바디를 파싱해야 발생하는 경로라 여기서는 다루지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("fakeai")
class OnboardingScheduleParseTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void parseSchedule_returnsParsedShifts() throws Exception {
        mockMvc.perform(post("/api/onboarding/schedule/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imageBase64": "ZmFrZQ==", "myRowLabel": null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shiftTypes").isNotEmpty())
                .andExpect(jsonPath("$.shifts").isNotEmpty());
    }

    @Test
    void parseSchedule_withMyRowLabel_alsoReturnsParsedShifts() throws Exception {
        mockMvc.perform(post("/api/onboarding/schedule/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"imageBase64": "ZmFrZQ==", "myRowLabel": "김OO"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shiftTypes").isNotEmpty());
    }
}

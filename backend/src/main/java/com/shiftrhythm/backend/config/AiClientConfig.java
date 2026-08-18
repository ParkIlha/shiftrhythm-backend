package com.shiftrhythm.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class AiClientConfig {

    @Bean
    public RestClient aiServerRestClient(@Value("${ai-server.base-url}") String baseUrl) {
        // 읽기 타임아웃은 넉넉해야 한다. AI 호출은 모델이 응답을 다 만들 때까지 걸린다.
        // 컴포즈 네트워크 실측: parse-disruption 4.5초, parse-schedule(vision) 6.6초.
        // 3초로는 세 엔드포인트가 전부 타임아웃 나서 규칙 기반 폴백으로만 돌았다.
        //
        // 15초로도 부족했다. vision 은 '하루 한 칸'씩 뱉어서 근무표가 길수록 오래 걸린다 —
        // 실측: 31일 1개월 8.7초, 61일 2개월 13.2초(출력 1981토큰). 실사용자는 두 달치를
        // 올리므로 15초는 사진이 조금만 복잡해져도 넘긴다. 사진 파싱은 온보딩에서 한 번,
        // 스피너를 띄우고 기다리는 화면이라 60초까지 열어둔다(다른 두 엔드포인트는 어차피 5초 안).
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(3))
                .withReadTimeout(Duration.ofSeconds(60));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}

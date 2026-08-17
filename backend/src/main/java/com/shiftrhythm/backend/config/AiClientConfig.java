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
        // 실측의 2.3배. 재시도 2회 감안해도 최악 30초. 연결은 같은 네트워크라 3초로 충분.
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(3))
                .withReadTimeout(Duration.ofSeconds(15));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}

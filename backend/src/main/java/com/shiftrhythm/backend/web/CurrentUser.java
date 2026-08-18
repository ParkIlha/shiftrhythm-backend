package com.shiftrhythm.backend.web;

import com.shiftrhythm.backend.domain.schedule.entity.UserProfile;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 로그인 없이 X-User-Id 헤더로 현재 사용자를 식별한다.
 *
 * 헤더가 없으면 데모 시드 사용자(id=1)로 동작한다. "새로 시작하기"는 헤더를 뺀 채
 * POST /api/onboarding/profile 을 호출해 새 사용자를 발급받고(응답의 userId),
 * 이후 모든 요청에 그 값을 헤더로 붙이는 흐름이다.
 *
 * ponytail: ThreadLocal 정적 홀더 — 요청 하나당 스레드 하나인 동기 MVC 전제라
 * 서비스마다 생성자 주입을 늘리지 않았다. 비동기/리액티브로 가면 @RequestScope 빈으로 바꿀 것.
 */
public final class CurrentUser {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private CurrentUser() {
    }

    /** 현재 사용자 id. 헤더가 없으면 데모 시드 사용자(1). */
    public static Long id() {
        Long id = CURRENT.get();
        return id == null ? UserProfile.SINGLETON_ID : id;
    }

    /** 헤더가 없으면 null. 신규 발급인지 판단해야 하는 프로필 등록에서만 쓴다. */
    public static Long idOrNull() {
        return CURRENT.get();
    }

    public static void set(Long id) {
        CURRENT.set(id);
    }

    public static void clear() {
        CURRENT.remove();
    }
}

@Component
class UserIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("X-User-Id");
        try {
            if (header != null && !header.isBlank()) {
                try {
                    CurrentUser.set(Long.valueOf(header.trim()));
                } catch (NumberFormatException e) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "X-User-Id는 숫자여야 합니다");
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            CurrentUser.clear();
        }
    }
}

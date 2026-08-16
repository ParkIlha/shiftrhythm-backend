# shiftrhythm-backend

교대근무자 생활 리듬 서비스의 백엔드. Spring Boot와 FastAPI 두 서버로 구성된다.

## 레포 구조

```text
project-root/
  backend/          Spring Boot (도메인 로직, 8080 외부 노출)
  ai-server/        FastAPI (AI 판단, 8000 내부망 전용)
  docker-compose.yml
  .env.example
```

## 통신 구조

```text
[사용자] → Nginx → Spring (8080)
                        ↓
                 FastAPI (8000)
                        ↓
                   Claude API
```

* Spring은 외부 API와 도메인 로직을 담당한다.
* FastAPI는 근무표/자연어 파싱과 수면·식사 리듬 설계를 담당한다.
* Spring ↔ FastAPI 통신은 Docker 내부망에서 이루어진다.
* 인프라(Docker Compose, Dockerfile, 네트워크, 배포)는 BE 담당이다.

## 역할 분담

### Spring

* 근무 일정 및 사용자 데이터 관리
* 근무 모드 판정
* 규칙 기반 수면 초안(`currentSleepBlock`) 생성
* 수면 가능 범위(`sleepWindow`) 및 식사 제약(`mealConstraints`) 계산
* AI 응답 검증 및 필요 시 clamp
* AI 호출 실패 시 규칙 기반 값으로 fallback
* 최종 생활 리듬 저장 및 클라이언트 응답

### FastAPI

* 근무표 이미지 파싱
* 재설계 자연어 파싱
* `currentSleepBlock`을 참고한 수면 블록 설계/재설계
* `sleepWindow`, `mealConstraints` 제약 준수
* 체크인 기록 및 당일 상황을 반영한 개인화
* 식사·간식 시각 및 사용자 안내 문구 생성

## 실행

```bash
cp .env.example .env
docker compose up --build
```

`.env`에 `ANTHROPIC_API_KEY` 등 필요한 환경변수를 설정한다.

| 서비스       |   포트 | 비고            |
| --------- | ---: | ------------- |
| backend   | 8080 | 외부 노출         |
| ai-server | 8000 | Docker 내부망 전용 |
| db        | 3306 | Docker 내부망 전용 |

# API Contract (Spring ↔ FastAPI)

Spring은 `AI_SERVER_BASE_URL`(기본 `http://ai-server:8000`)을 통해 FastAPI를 호출한다.

공통 정책:

* 타임아웃 3초
* 실패 시 1회 재시도
* 호출 실패 시 Spring에서 fallback 처리
* 요청/응답은 고정 스키마 사용

---

## `POST /parse-schedule`

근무표 이미지에서 근무 타입과 날짜별 일정을 추출한다.

### Request

```json
{
  "imageBase64": "string"
}
```

### Response

```json
{
  "shiftTypes": [
    {
      "shiftType": "DAY | EVENING | NIGHT",
      "startTime": "HH:MM",
      "endTime": "HH:MM"
    }
  ],
  "shifts": [
    {
      "date": "YYYY-MM-DD",
      "shiftType": "DAY | EVENING | NIGHT | OFF"
    }
  ]
}
```

---

## `POST /parse-disruption`

재설계를 위해 입력된 자연어를 구조화된 이벤트로 변환한다.

### Request

```json
{
  "rawText": "string",
  "shiftContext": {
    "currentShift": "string",
    "nextShift": "string",
    "scheduledClockOut": "string | null"
  }
}
```

### Response

```json
{
  "eventType": "SHIFT_END_DELAY | SHIFT_ADDED | SLEEP_SHORTAGE | APPOINTMENT_ADDED | OTHER",
  "delayMinutes": "int",
  "confirmedAt": "ISO8601",
  "reasonCategory": "LATE_CLOCKOUT | EARLY_CLOCKOUT | SHIFT_CHANGE | PERSONAL_SCHEDULE | OTHER"
}
```

---

## `POST /suggest-adjustment`

Spring이 계산한 수면 초안과 제약 조건을 바탕으로 AI가 수면·식사 블록을 설계하거나 재설계한다.

근무표 등록 직후, 체크인 이후 개인화, 일정 변경에 따른 재설계 상황에서 공통으로 사용한다.

### Request

```json
{
  "mode": "string",
  "sleepWindow": {
    "earliestSleepStart": "HH:MM",
    "latestSleepEnd": "HH:MM"
  },
  "currentSleepBlock": {
    "mainSleepStart": "HH:MM",
    "mainSleepEnd": "HH:MM",
    "supplementarySleepStart": "HH:MM | null",
    "supplementarySleepEnd": "HH:MM | null",
    "napMinutes": "int | null",
    "ankerBlockStart": "HH:MM | null",
    "ankerBlockEnd": "HH:MM | null"
  },
  "mealConstraints": {
    "bigMealCutoff": "HH:MM",
    "nightRestrictionStart": "HH:MM",
    "nightRestrictionEnd": "HH:MM"
  },
  "history": {
    "recentSleepStarts": ["HH:MM"],
    "recentCondition": ["int"],
    "recentSleepSatisfaction": ["int"],
    "recentSleepLatencyMinutes": ["int"],
    "recentNightHunger": ["int"],
    "rhythmPreference": "string | null"
  },
  "todayContext": "string | null"
}
```

### Response

```json
{
  "sleep": {
    "mainSleepStart": "HH:MM",
    "mainSleepEnd": "HH:MM",
    "supplementarySleepStart": "HH:MM | null",
    "supplementarySleepEnd": "HH:MM | null",
    "napMinutes": "int | null",
    "reason": "string"
  },
  "meal": {
    "mainMealTime": "HH:MM",
    "subMealTime": "HH:MM | null",
    "snackNeeded": "boolean",
    "snackTime": "HH:MM | null",
    "reason": "string"
  }
}
```

`sleepWindow`는 AI가 넘을 수 없는 수면 하드 제약이다. FastAPI가 반환한 결과는 Spring에서 한 번 더 검증한다.

상세 프롬프트 규칙, 개인화 기준, 모드별 설계 기준 및 clamp 정책은 별도 **AI 서버 구현 명세**를 따른다.

## 배포

EC2 배포는 API 엔드포인트 구현 이후 진행 예정이다.

현재는 로컬 `docker compose` 환경까지 검증 완료.

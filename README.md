# shiftrhythm-backend

교대근무자 생활 리듬 서비스의 백엔드. Spring(도메인 로직) + FastAPI(AI 판단) 2개 서버로 구성된다.

## 레포 구조
```
project-root/
  backend/          Spring Boot (도메인 로직, 8080 외부 노출)
  ai-server/        FastAPI (AI 판단, 8000 내부망 전용)
  docker-compose.yml
  .env.example
```

## 통신 구조
```
[사용자] → Nginx → Spring (8080, 외부 노출)
                        ↓ (Docker 내부망, http://ai-server:8000)
                     FastAPI (8000, 외부 노출 없음)
                        ↓
                     DB (MySQL, 내부망 전용)
```
- 인프라(Docker Compose, 양쪽 Dockerfile, 네트워크, 배포)는 BE 담당
- FastAPI 내부 로직은 AI 파트 담당, BE는 엔드포인트 시그니처만 정의

## 원칙
- 수면·식사 **시각 계산과 모드 판정은 백엔드(코드)** 가 결정론적으로 한다.
- AI는 **판단과 언어**만 담당한다. `/suggest-adjustment`는 절대시각이 아니라 분 단위 조정값만 반환한다.

## 실행
```bash
cp .env.example .env   # ANTHROPIC_API_KEY 등 채우기
docker compose up --build
```
| 서비스 | 포트 | 비고 |
|---|---|---|
| backend | 8080 | 외부 노출 |
| ai-server | 8000 | 내부망 전용, 호스트 미노출 |
| db | 3306 | 내부망 전용, 호스트 미노출 |

## API Contract (Spring ↔ FastAPI)

FastAPI(`ai-server`)가 Spring 내부망에 제공하는 API. Spring이 `AI_SERVER_BASE_URL`(기본 `http://ai-server:8000`)로 호출한다.

### B-1. `POST /parse-schedule` — 근무표 사진 → 일정
**Request**
```json
{ "imageBase64": "string (data URL 접두사 포함 가능)" }
```
**Response `200`**
```json
{
  "shiftTypes": [
    { "shiftType": "DAY | EVENING | NIGHT", "startTime": "HH:MM", "endTime": "HH:MM" }
  ],
  "shifts": [
    { "date": "YYYY-MM-DD", "shiftType": "DAY | EVENING | NIGHT | OFF" }
  ]
}
```
**Response `422`** — 근무표를 신뢰성 있게 읽을 수 없음
```json
{ "error": "IMAGE_UNREADABLE" }
```

### B-2. `POST /parse-disruption` — 재설계 자연어 → 이벤트
**Request**
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
**Response `200`**
```json
{
  "eventType": "SHIFT_END_DELAY | SHIFT_ADDED | SLEEP_SHORTAGE | APPOINTMENT_ADDED | OTHER",
  "delayMinutes": "int (앞당겨짐은 음수, 해당 없으면 0)",
  "confirmedAt": "ISO8601 (서버 생성)",
  "reasonCategory": "LATE_CLOCKOUT | EARLY_CLOCKOUT | SHIFT_CHANGE | PERSONAL_SCHEDULE | OTHER"
}
```
**Response `422`** — 근무·수면·일정과 무관한 입력
```json
{ "error": "PARSE_FAILED", "message": "근무 관련 내용으로 다시 입력해주세요" }
```

### B-3. `POST /suggest-adjustment` — 개인화 조정폭
**Request**
```json
{
  "mode": "string",
  "sleepBlock": {
    "earliestSleepStart": "HH:MM",
    "latestSleepStart": "HH:MM",
    "minSleepDurationMinutes": "int",
    "ankerSleepRequired": "boolean",
    "ankerBlockStart": "HH:MM | null",
    "ankerBlockEnd": "HH:MM | null"
  },
  "mealBlock": {
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
**Response `200`**
```json
{
  "sleep": { "adjustMinutes": "int (앞당김 음수, 미룸 양수)", "reason": "string (한국어 한 문장)" },
  "meal": { "adjustMinutes": "int", "snackNeeded": "boolean", "reason": "string" }
}
```
> 절대시각은 절대 반환하지 않는다 — 실제 시각 계산과 범위 clamp는 Spring이 한다.

전체 스키마 정의는 `ai-server/app/schemas.py` 참고. 상세 배경/용어는 노션 "ERD & 백엔드(AI) 로직" B-1/B-2/B-3 문서 기준.

## 배포
EC2 배포는 API 엔드포인트가 어느 정도 완성된 이후 진행 예정 (현재는 로컬 `docker compose`까지 검증 완료).

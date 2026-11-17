# API Contract (Spring ↔ FastAPI)

Spring은 `AI_SERVER_BASE_URL`(기본 `http://ai-server:8000`)을 통해 FastAPI를 호출한다.

공통 정책:

* 타임아웃: connect 3초 / read 60초 (두 달치 근무표 vision 파싱이 10초 이상 걸려서 넉넉히 둔다)
* 네트워크 오류·5xx는 1회 재시도한다. 422는 AI 서버의 판정이라 재시도하지 않는다.
* 실패 시 처리는 엔드포인트마다 다르다.
  * `/suggest-adjustment`: 규칙 기반 초안으로 fallback (FastAPI 자체도 AI 실패 시 초안을 그대로 돌려준다)
  * `/parse-schedule`, `/parse-disruption`: 대체할 규칙이 없으므로 `PARSE_FAILED`로 클라이언트에 알린다
* 요청/응답은 고정 스키마 사용

---

## `POST /parse-schedule`

근무표 이미지에서 근무 타입과 날짜별 일정을 추출한다.

### Request

```json
{
  "imageBase64": "string",
  "myRowLabel": "string | null",
  "year": "int | null",
  "month": "int | null"
}
```

* `myRowLabel`: 근무표에 여러 명이 있을 때 본인 행 이름. 없으면 행이 하나일 때만 성공한다.
* `year`, `month`: 표에 연/월이 안 적혀 있을 때 쓰는 폴백(선택). 현재 Spring은 보내지 않아 오늘 기준으로 채워진다.

### Response

```json
{
  "shiftTypes": [
    {
      "shiftType": "string (표에 적힌 코드 그대로: D, 주간, 1조 ...)",
      "mapped": "DAY | EVENING | NIGHT | OFF",
      "reason": "string | null",
      "startTime": "HH:MM | null",
      "endTime": "HH:MM | null"
    }
  ],
  "shifts": [
    {
      "date": "YYYY-MM-DD",
      "shiftType": "string (shiftTypes[].shiftType 코드, 쉬는 날은 OFF)"
    }
  ]
}
```

* 표에 근무 시간 범례가 없으면 2교대/3교대 프리셋으로 `startTime`/`endTime`을 채운다.
* 존재하지 않는 날짜(2/30 등)는 버린다.

### Error (422)

```json
{ "error": "ROW_LABEL_REQUIRED", "message": "string", "rowLabels": ["string"], "rowPreviews": ["string"] }
```

```json
{ "error": "IMAGE_UNREADABLE" }
```

`ROW_LABEL_REQUIRED`면 Spring이 행 목록을 클라이언트에 내려주고, 사용자가 고른 `myRowLabel`로 다시 호출한다.

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

### Error (422)

```json
{ "error": "PARSE_FAILED", "message": "string" }
```

입력이 일정 변경이 아니라고 AI가 판단하면 대신 답변을 `message`에 담아 보낸다. Spring은 이 메시지를 그대로 사용자에게 보여준다.

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
    "subMealTime": "HH:MM",
    "snackNeeded": "boolean",
    "snackTime": "HH:MM | null",
    "reason": "string"
  }
}
```

`sleepWindow`는 AI가 넘을 수 없는 수면 하드 제약이다. FastAPI가 반환한 결과는 Spring(`AiSleepMealValidator`)에서 한 번 더 검증한다.

* 수면: `sleepWindow` 안으로 clamp한 뒤, 규칙 기반 초안 기준 ±tolerance 범위로 한 번 더 clamp한다. 앵커 구간은 항상 포함한다.
* 식사: 큰 식사 컷오프와 야간 제한 구간을 피하도록 clamp한다.
* 두 끼(`mainMealTime`, `subMealTime`)는 모두 필수다. AI 값이 `HH:MM` 형식이 아니면 FastAPI가 안전값으로 바꿔 보낸다.

상세 프롬프트 규칙, 개인화 기준, 모드별 설계 기준 및 clamp 정책은 별도 **AI 서버 구현 명세**를 따른다.

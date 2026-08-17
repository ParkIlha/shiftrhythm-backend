from pydantic import BaseModel

# 계약(요청/응답)은 노션 "ERD & 백엔드(AI) 로직" B-1/B-2/B-3 기준.

# --- B-1 parse-schedule ---
class ScheduleReq(BaseModel):
    imageBase64: str
    # 아래 3개는 백엔드 온보딩이 아직 안 보내므로 선택값. 없으면:
    #   myRowLabel 없음 → 표에 행이 하나뿐일 때만 정상(여러 명이면 422)
    #   year/month 없음 → 오늘 기준 연/월로 조립
    myRowLabel: str | None = None
    year: int | None = None
    month: int | None = None

class ShiftTypeDef(BaseModel):
    shiftType: str                # 표에 적힌 코드 그대로 (D, 주간, 1조 ...). shifts 와 잇는 키
    # AI 가 추론한 의미. confidence 가 low 면 앱이 사용자에게 물어본다.
    mapped: str = "UNKNOWN"       # DAY | EVENING | NIGHT | OFF | UNKNOWN
    confidence: str = "low"       # high | medium | low
    reason: str | None = None     # 판단 근거 한 줄 (사용자에게 보여줄 수 있음)
    startTime: str | None = None  # 표에 시간표(범례) 있으면 채움, 없으면 null → 앱이 나중에 물어봄
    endTime: str | None = None

class ShiftDay(BaseModel):
    date: str        # YYYY-MM-DD (백엔드가 year+month+day 로 조립)
    shiftType: str   # 코드 그대로. 쉬는 날은 "OFF"

class ScheduleRes(BaseModel):
    shiftTypes: list[ShiftTypeDef]
    shifts: list[ShiftDay]


# --- B-2 parse-disruption ---
class ShiftContext(BaseModel):
    currentShift: str
    nextShift: str
    scheduledClockOut: str | None = None

class DisruptionReq(BaseModel):
    rawText: str
    shiftContext: ShiftContext

class DisruptionRes(BaseModel):
    eventType: str
    delayMinutes: int
    confirmedAt: str        # ISO8601, 서버가 생성
    reasonCategory: str     # LATE_CLOCKOUT | EARLY_CLOCKOUT | SHIFT_CHANGE | PERSONAL_SCHEDULE | OTHER


# --- B-3 suggest-adjustment (백엔드 DTO 기준: 절대시각 in/out) ---
class SleepWindow(BaseModel):
    """AI가 절대 넘을 수 없는 하드 제약."""
    earliestSleepStart: str
    latestSleepEnd: str

class CurrentSleepBlock(BaseModel):
    """규칙 기반 초안 — AI는 이 안에서 재배치한다."""
    mainSleepStart: str
    mainSleepEnd: str
    supplementarySleepStart: str | None = None
    supplementarySleepEnd: str | None = None
    napMinutes: int | None = None
    ankerBlockStart: str | None = None
    ankerBlockEnd: str | None = None

class MealConstraints(BaseModel):
    bigMealCutoff: str
    nightRestrictionStart: str
    nightRestrictionEnd: str

class History(BaseModel):
    recentSleepStarts: list[str] = []
    recentCondition: list[int] = []
    recentSleepSatisfaction: list[int] = []
    recentSleepLatencyMinutes: list[int] = []
    recentNightHunger: list[int] = []
    rhythmPreference: str | None = None

class SuggestReq(BaseModel):
    mode: str
    sleepWindow: SleepWindow
    currentSleepBlock: CurrentSleepBlock
    mealConstraints: MealConstraints
    history: History
    todayContext: str | None = None

class SleepSuggest(BaseModel):
    mainSleepStart: str
    mainSleepEnd: str
    supplementarySleepStart: str | None = None
    supplementarySleepEnd: str | None = None
    napMinutes: int | None = None
    reason: str

class MealSuggest(BaseModel):
    mainMealTime: str
    subMealTime: str | None = None
    snackNeeded: bool = False
    snackTime: str | None = None
    reason: str

class SuggestRes(BaseModel):
    sleep: SleepSuggest
    meal: MealSuggest

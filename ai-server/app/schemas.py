from pydantic import BaseModel

# 계약(요청/응답)은 노션 "ERD & 백엔드(AI) 로직" B-1/B-2/B-3 기준.

# --- B-1 parse-schedule ---
class ScheduleReq(BaseModel):
    imageBase64: str

class ShiftTypeDef(BaseModel):
    shiftType: str   # DAY | EVENING | NIGHT
    startTime: str   # HH:MM
    endTime: str

class ShiftDay(BaseModel):
    date: str        # YYYY-MM-DD
    shiftType: str   # DAY | EVENING | NIGHT | OFF

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
    reasonCategory: str     # LATE_CLOCKOUT | DINNER_GATHERING | SHIFT_CHANGE | OTHER


# --- B-3 suggest-adjustment ---
class SleepBlock(BaseModel):
    earliestSleepStart: str
    latestSleepStart: str
    minSleepDurationMinutes: int
    ankerSleepRequired: bool
    ankerBlockStart: str | None = None
    ankerBlockEnd: str | None = None

class MealBlock(BaseModel):
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
    sleepBlock: SleepBlock
    mealBlock: MealBlock
    history: History
    todayContext: str | None = None

class SleepAdjust(BaseModel):
    adjustMinutes: int
    reason: str

class MealAdjust(BaseModel):
    adjustMinutes: int
    snackNeeded: bool
    reason: str

class SuggestRes(BaseModel):
    sleep: SleepAdjust
    meal: MealAdjust

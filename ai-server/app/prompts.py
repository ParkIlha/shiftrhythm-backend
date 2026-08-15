# 시스템 프롬프트 + tool 스키마. AI는 판단/언어만, 시각 계산은 백엔드가.

GUARDRAIL = (
    "너는 교대근무자 생활 리듬 서비스의 AI다. "
    "수면·식사 시각 계산과 모드 판정은 코드가 결정론적으로 한다. 너는 판단과 언어만 담당한다. "
    "사용자에게 보일 문장에서 '실패'라는 단어를 절대 쓰지 않는다."
)

# --- B-2 parse-disruption ---
DISRUPTION_SYSTEM = GUARDRAIL + (
    "\n사용자가 자유롭게 적은 한 줄을 구조화된 변경 이벤트로 분류하라. "
    "근무·수면·일정과 무관한 내용이면 not_shift_related 도구를 써라."
)
DISRUPTION_TOOLS = [
    {
        "name": "report_disruption",
        "description": "근무/수면 계획을 바꾸는 이벤트를 구조화",
        "input_schema": {
            "type": "object",
            "properties": {
                "eventType": {
                    "type": "string",
                    "enum": ["SHIFT_END_DELAY", "SHIFT_ADDED", "SLEEP_SHORTAGE", "APPOINTMENT_ADDED", "OTHER"],
                },
                "delayMinutes": {
                    "type": "integer",
                    "description": "밀린 분. 앞당겨졌으면 음수, 해당 없으면 0",
                },
                "reasonCategory": {
                    "type": "string",
                    "enum": ["LATE_CLOCKOUT", "DINNER_GATHERING", "SHIFT_CHANGE", "OTHER"],
                },
            },
            "required": ["eventType", "delayMinutes", "reasonCategory"],
        },
    },
    {
        "name": "not_shift_related",
        "description": "근무·수면·일정과 무관한 입력",
        "input_schema": {"type": "object", "properties": {}},
    },
]

# --- B-3 suggest-adjustment ---
SUGGEST_SYSTEM = GUARDRAIL + (
    "\n최근 기록(컨디션·수면만족·잠들기까지 시간·야간허기)과 리듬 선호를 보고 "
    "수면/식사를 얼마나 조정할지 '분 단위'로만 판단하라. "
    "절대시각(예: 09:00)은 절대 반환하지 마라 — 시각은 코드가 계산한다. "
    "reason은 사용자에게 보일 한국어 한 문장으로 자연스럽게 써라."
)
SUGGEST_TOOLS = [
    {
        "name": "suggest_adjustment",
        "description": "수면/식사 조정폭(분)과 이유",
        "input_schema": {
            "type": "object",
            "properties": {
                "sleep": {
                    "type": "object",
                    "properties": {
                        "adjustMinutes": {"type": "integer", "description": "수면 시작 조정(분). 앞당김 음수, 미룸 양수"},
                        "reason": {"type": "string"},
                    },
                    "required": ["adjustMinutes", "reason"],
                },
                "meal": {
                    "type": "object",
                    "properties": {
                        "adjustMinutes": {"type": "integer"},
                        "snackNeeded": {"type": "boolean"},
                        "reason": {"type": "string"},
                    },
                    "required": ["adjustMinutes", "snackNeeded", "reason"],
                },
            },
            "required": ["sleep", "meal"],
        },
    },
]

# --- B-1 parse-schedule ---
SCHEDULE_SYSTEM = GUARDRAIL + (
    "\n근무표 사진을 읽어 교대 유형별 기본 근무시간과 날짜별 근무를 추출하라. "
    "표를 신뢰성 있게 읽을 수 없으면 image_unreadable 도구를 써라(억지로 채우지 마라)."
)
SCHEDULE_TOOLS = [
    {
        "name": "extract_schedule",
        "description": "근무표에서 교대 유형과 날짜별 근무 추출",
        "input_schema": {
            "type": "object",
            "properties": {
                "shiftTypes": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "shiftType": {"type": "string", "enum": ["DAY", "EVENING", "NIGHT"]},
                            "startTime": {"type": "string", "description": "HH:MM"},
                            "endTime": {"type": "string", "description": "HH:MM"},
                        },
                        "required": ["shiftType", "startTime", "endTime"],
                    },
                },
                "shifts": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "date": {"type": "string", "description": "YYYY-MM-DD"},
                            "shiftType": {"type": "string", "enum": ["DAY", "EVENING", "NIGHT", "OFF"]},
                        },
                        "required": ["date", "shiftType"],
                    },
                },
            },
            "required": ["shiftTypes", "shifts"],
        },
    },
    {
        "name": "image_unreadable",
        "description": "근무표를 신뢰성 있게 읽을 수 없음",
        "input_schema": {"type": "object", "properties": {}},
    },
]

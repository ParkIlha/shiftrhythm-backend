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
    "\n규칙 기반으로 계산된 수면 초안(currentSleepBlock)이 주어진다. "
    "최근 기록(컨디션·수면만족·잠들기까지 시간·야간허기)과 리듬 선호를 보고 그 초안을 미세 조정하라. "
    "\n반드시 지킬 것:"
    "\n- 모든 시각은 'HH:MM' 24시간 형식으로만 (예: 09:00, 07:30). 한 자리 시(9:00)나 24:00은 금지."
    "\n- 수면은 sleepWindow(earliestSleepStart ~ latestSleepEnd) 밖으로 절대 나갈 수 없다."
    "\n- 초안에서 크게 벗어나지 마라. 조정은 보통 1시간 이내가 적절하다."
    "\n- 초안보다 수면을 짧게 만들지 마라. 조정할 근거가 없으면 초안 값을 그대로 반환하라."
    "\n- ankerBlock이 있으면 그 구간과 최대한 겹치게 유지하라."
    "\n- 주요식사는 bigMealCutoff 이후로 미루지 말고, "
    "nightRestrictionStart~End 구간(야간 소화 제한)에는 배치하지 마라."
    "\n- reason은 사용자에게 보일 한국어 한 문장으로 자연스럽게."
)
SUGGEST_TOOLS = [
    {
        "name": "suggest_adjustment",
        "description": "조정된 수면/식사 시각과 이유",
        "input_schema": {
            "type": "object",
            "properties": {
                "sleep": {
                    "type": "object",
                    "properties": {
                        "mainSleepStart": {"type": "string", "description": "HH:MM"},
                        "mainSleepEnd": {"type": "string", "description": "HH:MM"},
                        "supplementarySleepStart": {"type": "string", "description": "HH:MM. 초안에 있을 때만"},
                        "supplementarySleepEnd": {"type": "string", "description": "HH:MM. 초안에 있을 때만"},
                        "napMinutes": {"type": "integer", "description": "근무 중 파워냅(분). 없으면 생략"},
                        "reason": {"type": "string"},
                    },
                    "required": ["mainSleepStart", "mainSleepEnd", "reason"],
                },
                "meal": {
                    "type": "object",
                    "properties": {
                        "mainMealTime": {"type": "string", "description": "HH:MM, 주요 식사"},
                        "subMealTime": {"type": "string", "description": "HH:MM, 보조 식사. 불필요하면 생략"},
                        "snackNeeded": {"type": "boolean"},
                        "snackTime": {"type": "string", "description": "HH:MM. snackNeeded 일 때만"},
                        "reason": {"type": "string"},
                    },
                    "required": ["mainMealTime", "snackNeeded", "reason"],
                },
            },
            "required": ["sleep", "meal"],
        },
    },
]

# --- B-1 parse-schedule ---
SCHEDULE_SYSTEM = GUARDRAIL + (
    "\n교대근무표 사진을 읽는다(간호사·공장·경비 등 직종 무관). 표는 행=사람, 열=날짜, 칸=근무코드의 격자다. "
    "사용자가 지정한 '내 행'만 추출하고 다른 사람 행은 무시하라. "
    "근무코드는 표에 적힌 글자 그대로 써라(D, 주간, 1조 등). 임의로 DAY/NIGHT 같은 걸로 바꾸지 마라. 쉬는 날만 'OFF'로. "
    "표에 각 코드의 시간표(범례)가 있으면 startTime/endTime을 채우고, 없으면 비워라(추측 금지). "
    "날짜는 '며칠(1~31)'만 읽어라 — 연·월 조립은 코드가 한다. "
    "내 행을 찾을 수 없거나 표를 신뢰성 있게 읽을 수 없으면 image_unreadable 도구를 써라(억지로 채우지 마라)."
)
SCHEDULE_TOOLS = [
    {
        "name": "extract_schedule",
        "description": "근무표에서 '내 행'의 근무코드 정의와 날짜별 근무 추출",
        "input_schema": {
            "type": "object",
            "properties": {
                "shiftTypes": {
                    "type": "array",
                    "description": "내 행에 등장하는 근무코드별 정의(OFF 제외). 표에 시간표 없으면 start/end 생략",
                    "items": {
                        "type": "object",
                        "properties": {
                            "shiftType": {"type": "string", "description": "표에 적힌 코드 그대로"},
                            "startTime": {"type": "string", "description": "HH:MM, 표에 있을 때만"},
                            "endTime": {"type": "string", "description": "HH:MM, 표에 있을 때만"},
                        },
                        "required": ["shiftType"],
                    },
                },
                "shifts": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "day": {"type": "integer", "description": "며칠(1~31), 열 위치"},
                            "shiftType": {"type": "string", "description": "그 날 코드 그대로. 쉬면 OFF"},
                        },
                        "required": ["day", "shiftType"],
                    },
                },
            },
            "required": ["shiftTypes", "shifts"],
        },
    },
    {
        "name": "who_am_i",
        "description": "표에 여러 사람의 행이 있는데 어느 행이 사용자인지 지정되지 않음",
        "input_schema": {
            "type": "object",
            "properties": {
                "rowLabels": {
                    "type": "array",
                    "description": "표에서 읽은 행 라벨 목록 (사용자가 고를 수 있게)",
                    "items": {"type": "string"},
                },
            },
            "required": ["rowLabels"],
        },
    },
    {
        "name": "image_unreadable",
        "description": "내 행을 못 찾거나 근무표를 신뢰성 있게 읽을 수 없음",
        "input_schema": {"type": "object", "properties": {}},
    },
]

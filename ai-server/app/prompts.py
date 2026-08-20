# 시스템 프롬프트 + tool 스키마. AI는 판단/언어만, 시각 계산은 백엔드가.

GUARDRAIL = (
    "너는 교대근무자 생활 리듬 서비스의 AI다. "
    "수면·식사 시각 계산과 모드 판정은 코드가 결정론적으로 한다. 너는 판단과 언어만 담당한다. "
    "사용자에게 보일 문장에서 '실패'라는 단어를 절대 쓰지 않는다."
)

# --- B-2 parse-disruption ---
DISRUPTION_SYSTEM = GUARDRAIL + (
    "\n사용자가 자유롭게 적은 한 줄을 받아 아래 둘 중 하나를 반드시 호출하라."
    "\n- report_disruption: 오늘 계획을 다시 짜야 하는 입력. 퇴근 지연·근무 추가처럼 근무가 바뀐 경우뿐 아니라, "
    "회식·약속·병원처럼 수면을 밀어내는 개인 일정(PERSONAL_SCHEDULE), "
    "'카페인 때문에 잠이 안 온다'·'잠이 안 와서 늦게 잤다'처럼 실제 수면 시각이 밀리는 상황(SLEEP_SHORTAGE)도 포함이다. "
    "단 얼마나 밀리는지가 입력에 있거나 분명히 계산되는 경우에만 부른다('2시간 늦어졌다', '3시에 잠들었다')."
    "\n- not_shift_related: 계획을 바꿀 필요가 없거나, 바꿔야 하지만 얼마나 밀리는지 아직 모르는 입력. "
    "거절하지 말고 reply 에 답을 직접 써라. 밀린 시간을 모르면 숫자를 지어내지 말고 되물어라 "
    "(예: '잠이 안 오시는군요. 몇 시쯤 잠들 것 같으세요?'). "
    "수면·식사·카페인 질문이면 교대근무자 기준으로 실질적인 조언을 하고, "
    "정말 무관한 잡담이면 가볍게 받아준 뒤 계획 얘기로 돌아오게 유도하라."
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
                    # 백엔드 ReplanReason enum과 반드시 일치해야 한다(다르면 confirm 단계에서 500).
                    "description": "회식·모임·약속처럼 근무 자체와 무관한 개인 일정은 PERSONAL_SCHEDULE",
                    "enum": ["LATE_CLOCKOUT", "EARLY_CLOCKOUT", "SHIFT_CHANGE", "PERSONAL_SCHEDULE", "OTHER"],
                },
            },
            "required": ["eventType", "delayMinutes", "reasonCategory"],
        },
    },
    {
        "name": "not_shift_related",
        "description": "계획을 다시 짤 필요는 없는 입력 — reply 로 사용자에게 직접 답한다",
        "input_schema": {
            "type": "object",
            "properties": {
                "reply": {"type": "string", "description": "사용자에게 그대로 보일 한국어 1~2문장"},
            },
            "required": ["reply"],
        },
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
    "\n- 식사는 mainMealTime, subMealTime 두 끼 모두 반드시 채워라(생략 불가). "
    "위계 없는 대등한 두 끼이고 순서도 의미 없다 — 서로 4시간 이상 떨어뜨리기만 해라."
    "\n- 두 끼 모두 currentSleepBlock(주수면 mainSleepStart~End, 보조수면 supplementarySleepStart~End)과 "
    "절대 겹치면 안 된다."
    "\n- 두 끼는 반드시 기상(mainSleepEnd)부터 bigMealCutoff 사이에 넣어라. bigMealCutoff 는 "
    "'이 시각을 넘기면 소화가 취침을 방해한다'는 경계선이라 30분이라도 넘기면 안 된다 — "
    "컷오프와 취침 사이에는 간식만 가능하다."
    "\n- nightRestrictionStart~End 는 생체 야간(소화 능력이 가장 낮은 구간)이다. 가능하면 피하되, "
    "수면 회피·컷오프 때문에 불가피하면 그 안에 배치해도 된다 — 금지가 아니라 권장 회피 구간이다."
    "\n- 근무시간과 겹치는지는 신경 쓰지 마라. 근무 중 식사는 정상이다."
    "\n- recentNightHunger 가 높으면(평균 4 이상) snackNeeded=true 로 두고 "
    "주수면 시작 1시간 전쯤으로 snackTime 을 잡아라. 그렇지 않으면 굳이 넣지 마라. "
    "snackTime 도 수면 구간(보조수면 포함)과 겹치면 안 된다. 대신 bigMealCutoff 는 적용하지 않는다."
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
                        "mainMealTime": {"type": "string", "description": "HH:MM, 첫 번째 끼"},
                        "subMealTime": {"type": "string", "description": "HH:MM, 두 번째 끼. 첫 번째와 대등하며 생략 불가"},
                        "snackNeeded": {"type": "boolean"},
                        "snackTime": {"type": "string", "description": "HH:MM. snackNeeded 일 때만"},
                        "reason": {"type": "string"},
                    },
                    "required": ["mainMealTime", "subMealTime", "snackNeeded", "reason"],
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
    "근무코드(shiftType)는 표에 적힌 글자 그대로 써라(D, OF, 주간, 1조 등). 임의로 바꾸거나 정규화하지 마라. "
    "각 코드의 의미는 mapped 에 따로 적어라 — 표 전체(범례, 하단 집계행, 사용 패턴)를 근거로 판단하라. "
    "mapped는 반드시 DAY/EVENING/NIGHT/OFF 중 하나로 골라라(UNKNOWN 없음) — 백엔드가 이 값으로 "
    "근무유형별 시작·종료 시각을 확정하는데, 값 자체가 없으면 그 코드가 아예 처리 못 되고 근무표 등록이 "
    "통째로 실패한다. 확신이 없으면 표의 다른 단서(그 코드의 등장 빈도, 근무/휴무 패턴, 다른 코드와의 "
    "관계)로 가장 가능성 높은 값을 골라라 — '모르겠다'는 답은 허용되지 않는다. "
    "틀린 매핑은 사용자의 수면 계획을 망가뜨릴 수 있으므로, 특히 OFF(휴무)로 볼지 근무로 볼지 애매하면 "
    "OFF 쪽을 우선하라 — 실제로 근무일인데 OFF로 잘못 표시되는 것보다, 실제로 휴무인데 근무로 잘못 "
    "표시돼 근무 연속 규칙(예: 야간 근무 다음날 주간 근무 금지)을 위반하는 조합이 만들어지는 쪽이 "
    "훨씬 위험하다. "
    "표에 각 코드의 시간표(범례)가 있으면 startTime/endTime을 채우고, 없으면 비워라(추측 금지). "
    "날짜는 표에 실제로 적힌 값을 그대로 읽어라 — 칸이 몇 번째 열인지가 아니라 '9/1', '9월 1일' 같은 "
    "표기 자체를 읽어서 월(month, 1~12)을 채워라. 표가 두 달에 걸쳐 있으면(예: 8/28~9/3) 칸마다 실제 "
    "해당 월을 정확히 구분해서 넣어라 — 표 앞부분이라고 다 같은 달이라 가정하지 마라. "
    "월이 표에 아예 안 적혀 있으면 month를 생략해라 — 0이나 1 같은 값을 지어내지 마라(코드가 채운다). "
    "특히 헤더가 '1 2 3 …' 처럼 며칠만 있거나 요일(월·화·수)만 있는 표가 흔하다. "
    "요일의 '월'은 월요일이지 1월이 아니다. 이런 표는 month 없이 day만 채워라. "
    "연도도 마찬가지로 표에 적혀 있을 때만 year를 채워라. "
    "요일 표기(예: '9/1(일)')가 있으면 참고해서 월 판단의 근거로 삼아도 좋다. "
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
                    "description": "내 행에 등장하는 근무코드 전부(OFF 포함). 표에 시간표 없으면 start/end 생략",
                    "items": {
                        "type": "object",
                        "properties": {
                            "shiftType": {"type": "string", "description": "표에 적힌 코드 그대로"},
                            "mapped": {
                                "type": "string",
                                "enum": ["DAY", "EVENING", "NIGHT", "OFF"],
                                "description": "이 코드의 의미. 확신 없어도 가장 가능성 높은 값을 고른다(생략 불가)",
                            },
                            "reason": {"type": "string", "description": "판단 근거 한 줄"},
                            "startTime": {"type": "string", "description": "HH:MM, 표에 있을 때만"},
                            "endTime": {"type": "string", "description": "HH:MM, 표에 있을 때만"},
                        },
                        "required": ["shiftType", "mapped", "reason"],
                    },
                },
                "shifts": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "day": {"type": "integer", "description": "표에 적힌 며칠(1~31)"},
                            "month": {"type": "integer", "description": "표에 적힌 실제 월(1~12). 열 순서가 아니라 표기 그대로. 표에 월이 안 적혀 있으면 생략"},
                            "year": {"type": "integer", "description": "표에 연도가 적혀 있으면 그 값(예: 2024). 안 보이면 생략"},
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
                # ponytail: 이전엔 행별 근무 미리보기를 "day/shiftType 중첩 객체"로 시도했다가
                # 40행 격자에서 모델이 빈 배열/평탄화된 배열을 냈다(그래서 한동안 라벨만 줬다).
                # 이번엔 rowLabels와 똑같이 "평평한 문자열 배열"로만 요청한다 — 이미 라벨 자체가
                # 이 모양으로 잘 나오고 있으니, 같은 모양을 하나 더 늘리는 것뿐이라 실패 확률이 낮다.
                # required에는 안 넣는다 — 못 만들면 그냥 라벨만 쓰던 대로 동작해야 한다(안전한 폴백).
                "rowPreviews": {
                    "type": "array",
                    "description": (
                        "rowLabels와 같은 순서·같은 길이(짝이 안 맞으면 아예 생략해라). "
                        "각 행 앞쪽 근무코드를 공백으로 이어붙인 짧은 문자열 하나씩, 최대 7개"
                        "(예: 'N N OFF OFF D D E'). 사용자가 본인 줄을 알아보는 용도이므로 "
                        "표에 적힌 코드 그대로만 쓰고 해석·가공하지 마라."
                    ),
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

from datetime import date
from fastapi import APIRouter
from fastapi.responses import JSONResponse
from app.schemas import ScheduleReq, ScheduleRes
from app.claude_client import call_tool, decode_image
from app.config import MODEL_VISION, MAX_TOKENS_VISION
from app import prompts

router = APIRouter()

# 표에 범례가 없어 AI가 시각을 못 읽었을 때 채워 보내는 기본값(백엔드 shiftTypeDefaults 는 시각이
# 필수라 null 이면 확정 단계에서 막힌다). AI 에게 추측시키지 않고 코드가 결정론적으로 채운다.
# 표에 EVENING 이 있으면 3교대, 없으면 2교대로 본다. OFF/UNKNOWN 은 그대로 null.
SHIFT_TIME_PRESETS = {
    2: {"DAY": ("08:00", "20:00"), "NIGHT": ("20:00", "08:00")},
    3: {"DAY": ("06:00", "14:00"), "EVENING": ("14:00", "22:00"), "NIGHT": ("22:00", "06:00")},
}


def is_month_guessed(req_month, ai_shifts):
    """요청에 month가 없고, AI도 단 하나의 shift에서조차 month를 못 읽었으면 전부 오늘 기준
    추측이다 — 이땐 shifts[].date의 절대 연/월을 신뢰할 수 없다는 신호로 true를 반환한다."""
    return req_month is None and all(s.get("month") is None for s in ai_shifts)


def fill_default_times(shift_types):
    """시각이 빈 근무코드에 교대 프리셋을 채운다(있는 값은 건드리지 않는다)."""
    preset = SHIFT_TIME_PRESETS[3 if any(t.get("mapped") == "EVENING" for t in shift_types) else 2]
    for t in shift_types:
        if not (t.get("startTime") and t.get("endTime")) and t.get("mapped") in preset:
            t["startTime"], t["endTime"] = preset[t["mapped"]]
    return shift_types


@router.post("/parse-schedule")
def parse_schedule(req: ScheduleReq):
    media, data64 = decode_image(req.imageBase64)
    ask = (
        f"'{req.myRowLabel}' 행의 근무만 추출해줘." if req.myRowLabel
        else "이 근무표를 추출해줘. 행이 여러 명이면 who_am_i 도구를 써라."
    )
    content = [
        # 캐시 지점을 이미지에 찍어서 system+tools+사진까지를 프리픽스로 만든다. 근무표는
        # '행 목록 받기 → 사용자가 행 고르고 같은 사진으로 재호출' 2단계라, 두 번째 호출이
        # 이 프리픽스(4500토큰)를 0.1배 요금으로 재사용한다(TTL 5분). 뒤의 ask 문구는 호출마다
        # 달라지므로 반드시 캐시 지점 뒤에 와야 한다 — 순서를 바꾸면 매번 캐시 미스다.
        {"type": "image", "source": {"type": "base64", "media_type": media, "data": data64},
         "cache_control": {"type": "ephemeral"}},
        {"type": "text", "text": ask},
    ]
    # 근무표 추출은 하루당 한 칸씩 뱉어서 출력이 길다 — 두 달치면 2000 토큰 넘으므로 상한을 따로 준다.
    name, data = call_tool(MODEL_VISION, prompts.SCHEDULE_SYSTEM, content, prompts.SCHEDULE_TOOLS,
                           max_tokens=MAX_TOKENS_VISION)
    if name == "who_am_i":
        row_labels = data.get("rowLabels", [])
        row_previews = data.get("rowPreviews", [])
        body = {
            "error": "ROW_LABEL_REQUIRED",
            "message": "근무표에 여러 명이 있어요. 본인 행을 선택해주세요",
            # 백엔드 RowLabelRequiredBody 가 이 이름으로 읽는다. 바꾸면 행 목록이 null 이 된다.
            "rowLabels": row_labels,
        }
        # 길이가 안 맞으면(모델이 몇 개 빼먹은 경우) 어느 라벨과 어느 미리보기가 짝인지 알 수
        # 없으므로 통째로 뺀다 — 잘못 짝지어 보여주는 것보다 안 보여주는 게 안전하다.
        if row_previews and len(row_previews) == len(row_labels):
            body["rowPreviews"] = row_previews
        return JSONResponse(status_code=422, content=body)
    if name != "extract_schedule" or not data.get("shifts"):
        return JSONResponse(status_code=422, content={"error": "IMAGE_UNREADABLE"})
    # AI가 칸마다 실제 월(과 있으면 연도)을 읽어오므로, 표가 두 달에 걸쳐 있어도(예: 8/28~9/3)
    # 칸별로 올바른 달로 조립된다. 표에 월/연도가 안 적혀 있으면 요청값 → 오늘 기준으로 채운다.
    today = date.today()
    month_guessed = is_month_guessed(req.month, data["shifts"])
    shifts = []
    for s in data["shifts"]:
        month = s.get("month") or req.month or today.month
        if not 1 <= month <= 12 or not 1 <= s.get("day", 0) <= 31:
            continue  # 지어낸 날짜는 백엔드 LocalDate.parse 에서 터지므로 여기서 버린다
        year = s.get("year") or req.year or _infer_year(month, today)
        shifts.append({"date": f"{year:04d}-{month:02d}-{s['day']:02d}", "shiftType": s["shiftType"]})
    if not shifts:
        return JSONResponse(status_code=422, content={"error": "IMAGE_UNREADABLE"})
    return ScheduleRes(shiftTypes=fill_default_times(data["shiftTypes"]), shifts=shifts, monthGuessed=month_guessed)


def _infer_year(month: int, today: date) -> int:
    """연도가 사진에 안 보일 때: 표는 보통 오늘과 가까운 시점의 근무표이므로, 오늘 월과
    6개월 이상 차이 나는 방향으로는 연도를 한 해 옮겨서 더 가까운 쪽으로 맞춘다.
    예) 오늘 1월인데 표가 12월이면 → 작년 12월(막 지난 달)로, 오늘 12월인데 표가 1월이면 → 내년 1월로.
    """
    diff = month - today.month
    if diff > 6:
        return today.year - 1
    if diff < -6:
        return today.year + 1
    return today.year


if __name__ == "__main__":
    # ponytail 자체점검: 프리셋 채우기 (API 호출 없음 = 요금 없음)
    two = fill_default_times([{"shiftType": "주", "mapped": "DAY"}, {"shiftType": "야", "mapped": "NIGHT"},
                              {"shiftType": "휴", "mapped": "OFF"}])
    assert (two[0]["startTime"], two[0]["endTime"]) == ("08:00", "20:00"), two[0]
    assert (two[1]["startTime"], two[1]["endTime"]) == ("20:00", "08:00"), two[1]
    assert two[2].get("startTime") is None                      # 휴무는 시각 없음

    three = fill_default_times([{"shiftType": "D", "mapped": "DAY"}, {"shiftType": "E", "mapped": "EVENING"},
                                {"shiftType": "N", "mapped": "NIGHT"}, {"shiftType": "?", "mapped": "UNKNOWN"}])
    assert [t.get("startTime") for t in three] == ["06:00", "14:00", "22:00", None], three

    read = fill_default_times([{"shiftType": "D", "mapped": "DAY", "startTime": "07:00", "endTime": "15:00"}])
    assert (read[0]["startTime"], read[0]["endTime"]) == ("07:00", "15:00")   # 읽어온 값은 그대로

    half = fill_default_times([{"shiftType": "D", "mapped": "DAY", "startTime": "07:00"}])
    assert (half[0]["startTime"], half[0]["endTime"]) == ("08:00", "20:00")   # 반쪽이면 프리셋으로

    assert is_month_guessed(None, [{"month": None}, {"month": None}]) is True     # 요청도 AI도 다 없음
    assert is_month_guessed(9, [{"month": None}, {"month": None}]) is False       # 요청에 month 있음
    assert is_month_guessed(None, [{"month": None}, {"month": 9}]) is False       # AI가 하나라도 읽음
    print("ok")

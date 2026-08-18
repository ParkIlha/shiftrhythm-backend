from datetime import date
from fastapi import APIRouter
from fastapi.responses import JSONResponse
from app.schemas import ScheduleReq, ScheduleRes
from app.claude_client import call_tool, decode_image
from app.config import MODEL_VISION, MAX_TOKENS_VISION
from app import prompts

router = APIRouter()


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
        return JSONResponse(status_code=422, content={
            "error": "ROW_LABEL_REQUIRED",
            "message": "근무표에 여러 명이 있어요. 본인 행을 선택해주세요",
            # 백엔드 RowLabelRequiredBody 가 이 이름으로 읽는다. 바꾸면 행 목록이 null 이 된다.
            "rowLabels": data.get("rowLabels", []),
        })
    if name != "extract_schedule" or not data.get("shifts"):
        return JSONResponse(status_code=422, content={"error": "IMAGE_UNREADABLE"})
    # AI가 칸마다 실제 월(과 있으면 연도)을 읽어오므로, 표가 두 달에 걸쳐 있어도(예: 8/28~9/3)
    # 칸별로 올바른 달로 조립된다. 표에 월/연도가 안 적혀 있으면 요청값 → 오늘 기준으로 채운다.
    today = date.today()
    shifts = []
    for s in data["shifts"]:
        month = s.get("month") or req.month or today.month
        if not 1 <= month <= 12 or not 1 <= s.get("day", 0) <= 31:
            continue  # 지어낸 날짜는 백엔드 LocalDate.parse 에서 터지므로 여기서 버린다
        year = s.get("year") or req.year or _infer_year(month, today)
        shifts.append({"date": f"{year:04d}-{month:02d}-{s['day']:02d}", "shiftType": s["shiftType"]})
    if not shifts:
        return JSONResponse(status_code=422, content={"error": "IMAGE_UNREADABLE"})
    return ScheduleRes(shiftTypes=data["shiftTypes"], shifts=shifts)


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

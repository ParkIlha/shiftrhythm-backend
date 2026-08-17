from datetime import date
from fastapi import APIRouter
from fastapi.responses import JSONResponse
from app.schemas import ScheduleReq, ScheduleRes
from app.claude_client import call_tool, decode_image
from app.config import MODEL_VISION
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
        {"type": "image", "source": {"type": "base64", "media_type": media, "data": data64}},
        {"type": "text", "text": ask},
    ]
    name, data = call_tool(MODEL_VISION, prompts.SCHEDULE_SYSTEM, content, prompts.SCHEDULE_TOOLS)
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
    # 칸별로 올바른 달로 조립된다. year가 안 보인 칸은 오늘 날짜 기준으로 가장 그럴듯한 연도로 추론한다.
    today = date.today()
    shifts = [
        {
            "date": f"{s.get('year') or req.year or _infer_year(s['month'], today):04d}-{s['month']:02d}-{s['day']:02d}",
            "shiftType": s["shiftType"],
        }
        for s in data["shifts"]
    ]
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

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
            "candidates": data.get("rowLabels", []),
        })
    if name != "extract_schedule" or not data.get("shifts"):
        return JSONResponse(status_code=422, content={"error": "IMAGE_UNREADABLE"})
    # AI는 '며칠'만 읽고, 실제 날짜(YYYY-MM-DD)는 코드가 조립한다.
    today = date.today()
    year, month = req.year or today.year, req.month or today.month
    shifts = [
        {"date": f"{year:04d}-{month:02d}-{s['day']:02d}", "shiftType": s["shiftType"]}
        for s in data["shifts"]
    ]
    return ScheduleRes(shiftTypes=data["shiftTypes"], shifts=shifts)

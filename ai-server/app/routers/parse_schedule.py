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
    content = [
        {"type": "image", "source": {"type": "base64", "media_type": media, "data": data64}},
        {"type": "text", "text": "이 근무표를 추출해줘."},
    ]
    name, data = call_tool(MODEL_VISION, prompts.SCHEDULE_SYSTEM, content, prompts.SCHEDULE_TOOLS)
    if name != "extract_schedule" or not data.get("shifts"):
        return JSONResponse(status_code=422, content={"error": "IMAGE_UNREADABLE"})
    return ScheduleRes(**data)

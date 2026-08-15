from datetime import datetime
from fastapi import APIRouter
from fastapi.responses import JSONResponse
from app.schemas import DisruptionReq, DisruptionRes
from app.claude_client import call_tool
from app.config import MODEL_FAST
from app import prompts

router = APIRouter()


@router.post("/parse-disruption")
def parse_disruption(req: DisruptionReq):
    ctx = req.shiftContext
    user = (
        f"입력: {req.rawText}\n"
        f"현재근무: {ctx.currentShift}, 다음근무: {ctx.nextShift}, 예정퇴근: {ctx.scheduledClockOut}"
    )
    name, data = call_tool(MODEL_FAST, prompts.DISRUPTION_SYSTEM, user, prompts.DISRUPTION_TOOLS)
    if name != "report_disruption":
        return JSONResponse(
            status_code=422,
            content={"error": "PARSE_FAILED", "message": "근무 관련 내용으로 다시 입력해주세요"},
        )
    return DisruptionRes(
        eventType=data["eventType"],
        delayMinutes=data["delayMinutes"],
        confirmedAt=datetime.now().isoformat(timespec="seconds"),
        reasonCategory=data["reasonCategory"],
    )

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
    # ponytail: 조언 답변이 422(PARSE_FAILED) 본문의 message 를 타고 나간다 — 프론트가 이미 그 필드를
    # AI 말풍선에 띄우고 있어서 배관을 새로 안 깔았다. 조언과 재계획 미리보기를 "같이" 내려야 하면
    # 그때 200 응답에 reply 필드를 추가할 것.
    if name != "report_disruption":
        return JSONResponse(
            status_code=422,
            content={
                "error": "PARSE_FAILED",
                "message": (data or {}).get("reply") or "근무 관련 내용으로 다시 입력해주세요",
            },
        )
    return DisruptionRes(
        eventType=data["eventType"],
        delayMinutes=data["delayMinutes"],
        confirmedAt=datetime.now().isoformat(timespec="seconds"),
        reasonCategory=data["reasonCategory"],
    )

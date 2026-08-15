from fastapi import APIRouter
from app.schemas import SuggestReq, SuggestRes, SleepAdjust, MealAdjust
from app.claude_client import call_tool
from app.config import MODEL_FAST
from app import prompts

router = APIRouter()


@router.post("/suggest-adjustment", response_model=SuggestRes)
def suggest_adjustment(req: SuggestReq):
    user = req.model_dump_json(indent=2)
    _, data = call_tool(MODEL_FAST, prompts.SUGGEST_SYSTEM, user, prompts.SUGGEST_TOOLS)
    if not data:
        # tool 강제라 거의 안 옴. 안전값: 조정 없음(백엔드 규칙 기본값 유지).
        return SuggestRes(
            sleep=SleepAdjust(adjustMinutes=0, reason=""),
            meal=MealAdjust(adjustMinutes=0, snackNeeded=False, reason=""),
        )
    # 범위 clamp 는 백엔드가 하므로 그대로 통과.
    return SuggestRes(**data)

from datetime import datetime
from fastapi import APIRouter
from app.schemas import SuggestReq, SuggestRes, SleepSuggest, MealSuggest
from app.claude_client import call_tool
from app.config import MODEL_FAST
from app import prompts

router = APIRouter()

FALLBACK_REASON = "오늘은 기존 계획을 그대로 유지할게요."


def hhmm(value, default=None):
    """HH:MM 이 아니면 default. 백엔드가 LocalTime.parse 를 가드 없이 하므로 여기서 보장한다."""
    try:
        return datetime.strptime(value, "%H:%M").strftime("%H:%M")
    except (TypeError, ValueError):
        return default


MAIN_MEAL_BEFORE_CUTOFF_MINUTES = 90
SECOND_MEAL_GAP_MINUTES = 300


def _to_min(hm: str) -> int:
    h, m = hm.split(":")
    return int(h) * 60 + int(m)


def _to_hhmm(total: int) -> str:
    return f"{total // 60 % 24:02d}:{total % 60:02d}"


def _safe_main_meal(mc) -> str:
    """AI 값을 쓸 수 없을 때의 첫 번째 끼 시각.

    bigMealCutoff 는 "이 시각 이후 금지"인 경계선이라 그대로 추천값으로 쓰면 식사가 취침에 딱 붙는다.
    90분 앞으로 당길 뿐, 생체 야간 구간 회피는 백엔드 clampMeal 이 어차피 다시 하므로 여기선 안 한다.
    """
    return _to_hhmm(_to_min(mc.bigMealCutoff) - MAIN_MEAL_BEFORE_CUTOFF_MINUTES)


def _safe_second_meal(req: SuggestReq) -> str:
    """AI 값을 쓸 수 없을 때의 두 번째 끼 시각 — 두 끼는 둘 다 필수라 폴백에도 반드시 있어야 한다.

    첫 끼의 5시간 전. 그러면 기상 전(=수면 중)으로 밀리는 짧은 각성 구간에서는 기상 1시간 후로.
    """
    first = _to_min(_safe_main_meal(req.mealConstraints))
    wake = _to_min(req.currentSleepBlock.mainSleepEnd)
    awake_before_first = (first - wake) % 1440
    if awake_before_first >= SECOND_MEAL_GAP_MINUTES + 60:
        return _to_hhmm(first - SECOND_MEAL_GAP_MINUTES)
    return _to_hhmm(wake + 60)


def _draft(req: SuggestReq, reason: str) -> SuggestRes:
    """규칙 기반 초안을 그대로 반환 — AI 실패 시 안전값."""
    c = req.currentSleepBlock
    return SuggestRes(
        sleep=SleepSuggest(
            mainSleepStart=c.mainSleepStart,
            mainSleepEnd=c.mainSleepEnd,
            supplementarySleepStart=c.supplementarySleepStart,
            supplementarySleepEnd=c.supplementarySleepEnd,
            napMinutes=c.napMinutes,
            reason=reason,
        ),
        meal=MealSuggest(
            mainMealTime=_safe_main_meal(req.mealConstraints),
            subMealTime=_safe_second_meal(req),
            reason=reason,
        ),
    )


@router.post("/suggest-adjustment", response_model=SuggestRes)
def suggest_adjustment(req: SuggestReq):
    _, data = call_tool(MODEL_FAST, prompts.SUGGEST_SYSTEM, req.model_dump_json(indent=2), prompts.SUGGEST_TOOLS)
    if not data:
        return _draft(req, FALLBACK_REASON)

    c = req.currentSleepBlock
    sleep, meal = data.get("sleep", {}), data.get("meal", {})

    # 필수 시각이 깨졌으면 그 값만 초안으로 되돌린다. 범위 clamp 는 백엔드가.
    start = hhmm(sleep.get("mainSleepStart"), c.mainSleepStart)
    end = hhmm(sleep.get("mainSleepEnd"), c.mainSleepEnd)
    main_meal = hhmm(meal.get("mainMealTime"), _safe_main_meal(req.mealConstraints))
    snack_time = hhmm(meal.get("snackTime"))

    return SuggestRes(
        sleep=SleepSuggest(
            mainSleepStart=start,
            mainSleepEnd=end,
            # 초안에 보조수면이 없으면 AI가 만들어내도 무시(분할 여부는 규칙이 정한다)
            supplementarySleepStart=(
                hhmm(sleep.get("supplementarySleepStart"), c.supplementarySleepStart)
                if c.supplementarySleepStart else None
            ),
            supplementarySleepEnd=(
                hhmm(sleep.get("supplementarySleepEnd"), c.supplementarySleepEnd)
                if c.supplementarySleepStart else None
            ),
            napMinutes=sleep.get("napMinutes", c.napMinutes),
            reason=sleep.get("reason") or FALLBACK_REASON,
        ),
        meal=MealSuggest(
            mainMealTime=main_meal,
            subMealTime=hhmm(meal.get("subMealTime"), _safe_second_meal(req)),
            snackNeeded=bool(meal.get("snackNeeded")) and snack_time is not None,
            snackTime=snack_time,
            reason=meal.get("reason") or FALLBACK_REASON,
        ),
    )


if __name__ == "__main__":
    from types import SimpleNamespace

    # ponytail 자체점검: 시각 가드 + AI 실패 시 초안 폴백
    assert hhmm("09:00") == "09:00"
    assert hhmm("7:30") == "07:30"           # 한자리 시 정규화
    assert hhmm("24:00", "X") == "X"         # LocalTime.parse 가 터지는 값
    assert hhmm("나중에", "X") == "X"
    assert hhmm(None, "X") == "X"
    assert hhmm(None) is None

    req = SuggestReq(
        mode="NIGHT",
        sleepWindow={"earliestSleepStart": "07:30", "latestSleepEnd": "21:00"},
        currentSleepBlock={"mainSleepStart": "07:30", "mainSleepEnd": "12:00"},
        mealConstraints={"bigMealCutoff": "06:00", "nightRestrictionStart": "00:00", "nightRestrictionEnd": "06:00"},
        history={},
    )
    d = _draft(req, FALLBACK_REASON)
    assert (d.sleep.mainSleepStart, d.sleep.mainSleepEnd) == ("07:30", "12:00")
    # 컷오프 경계선(06:00)에 딱 붙이지 않고 90분 앞으로 — 자정 넘김 포함
    assert d.meal.mainMealTime == "04:30" and d.meal.snackNeeded is False
    assert _safe_main_meal(SimpleNamespace(bigMealCutoff="01:00")) == "23:30"

    # 두 끼는 둘 다 필수 — 폴백도 두 번째 끼를 채운다(첫 끼 5시간 전, 기상 12:00 이후)
    assert d.meal.subMealTime == "23:30"
    # 각성 구간이 짧아 5시간 전이 수면 중으로 밀리면 기상 1시간 후로
    short = req.model_copy(update={
        "currentSleepBlock": req.currentSleepBlock.model_copy(update={"mainSleepEnd": "08:00"}),
        "mealConstraints": req.mealConstraints.model_copy(update={"bigMealCutoff": "12:00"}),
    })
    assert _safe_main_meal(short.mealConstraints) == "10:30" and _safe_second_meal(short) == "09:00"
    print("ok")

"""AI 응답 '품질' 점검. 호출 성공이 아니라 내용이 맞는지 본다.

    python eval.py              # 전체
    python eval.py disruption   # 하나만 (disruption | suggest | schedule)

LLM은 같은 입력에도 문장이 매번 달라서 정답 비교가 불가능하다. 대신 반드시 지켜져야 하는
성질(불변식)만 검사한다. 실제 Claude를 호출하므로 요금이 든다 — 자체점검(__main__)과 분리한 이유.
"""
import sys
from datetime import datetime

from fastapi.responses import JSONResponse

from app.routers.parse_disruption import parse_disruption
from app.routers.suggest_adjustment import suggest_adjustment
from app.routers.parse_schedule import parse_schedule
from app.schemas import DisruptionReq, SuggestReq, ScheduleReq

IMAGE = "../../hswr-42-1-258-f004.png"  # 실제 병동 근무표. 간호사7 = 야간전담조
MY_ROW = "간호사7"

results = []


def check(name, fn):
    try:
        fn()
        results.append((True, name, ""))
        print(f"  PASS  {name}")
    except AssertionError as e:
        results.append((False, name, str(e)))
        print(f"  FAIL  {name}\n        {e}")
    except Exception as e:
        results.append((False, name, repr(e)))
        print(f"  ERR   {name}\n        {e!r}")


def rejected(res):
    return isinstance(res, JSONResponse)


# ---------- parse-disruption: 자연어 → 숫자를 제대로 뽑는가 ----------

def disruption(text, current="NIGHT", nxt="NIGHT", clock_out="07:00"):
    return parse_disruption(DisruptionReq(
        rawText=text,
        shiftContext={"currentShift": current, "nextShift": nxt, "scheduledClockOut": clock_out},
    ))


def eval_disruption():
    print("\n[parse-disruption] 자연어에서 지연시간·분류를 뽑는가")

    def case_explicit():
        r = disruption("인수인계 늦어져서 40분 늦게 퇴근할 것 같아")
        assert not rejected(r), "근무 관련인데 거부됨"
        assert r.delayMinutes == 40, f"40분이어야 하는데 {r.delayMinutes}"
        assert r.eventType == "SHIFT_END_DELAY", r.eventType

    def case_korean_unit():
        # "1시간 반" 같은 한국어 표현을 분으로 환산하는가
        r = disruption("한 시간 반 정도 늦어질 것 같아")
        assert not rejected(r), "근무 관련인데 거부됨"
        assert r.delayMinutes == 90, f"90분이어야 하는데 {r.delayMinutes}"

    def case_earlier_is_negative():
        # 앞당겨진 건 음수여야 한다 (스키마 규칙)
        r = disruption("오늘 30분 일찍 끝났어")
        assert not rejected(r), "근무 관련인데 거부됨"
        assert r.delayMinutes < 0, f"앞당김은 음수여야 하는데 {r.delayMinutes}"

    def case_dinner():
        r = disruption("퇴근하고 회식 가기로 했어", current="EVENING", nxt="EVENING", clock_out="23:00")
        assert not rejected(r), "근무 관련인데 거부됨"
        assert r.reasonCategory == "DINNER_GATHERING", f"회식인데 {r.reasonCategory}"

    def case_irrelevant():
        r = disruption("오늘 점심 뭐 먹지")
        assert rejected(r), "무관한 입력인데 통과됨"

    def case_confirmed_at():
        r = disruption("30분 늦어져")
        assert not rejected(r)
        datetime.fromisoformat(r.confirmedAt)  # 파싱 안 되면 예외

    for f in (case_explicit, case_korean_unit, case_earlier_is_negative,
              case_dinner, case_irrelevant, case_confirmed_at):
        check(f.__name__, f)


# ---------- suggest-adjustment: 제약을 지키는가 ----------

NIGHT_REQ = {
    "mode": "NIGHT",
    "sleepWindow": {"earliestSleepStart": "07:30", "latestSleepEnd": "21:00"},
    "currentSleepBlock": {
        "mainSleepStart": "07:30", "mainSleepEnd": "12:00",
        "supplementarySleepStart": "19:00", "supplementarySleepEnd": "21:00",
    },
    "mealConstraints": {"bigMealCutoff": "06:00", "nightRestrictionStart": "00:00", "nightRestrictionEnd": "06:00"},
    "history": {
        "recentSleepStarts": ["09:00", "09:30", "10:00"], "recentCondition": [3, 2, 2],
        "recentSleepSatisfaction": [2, 2, 1], "recentSleepLatencyMinutes": [40, 50, 60],
        "recentNightHunger": [3, 4, 4], "rhythmPreference": "BALANCED",
    },
    "todayContext": "야간 3일차, 계속 못 자고 새벽에 배고픔",
}


def mins(hhmm_str):
    h, m = hhmm_str.split(":")
    return int(h) * 60 + int(m)


def offset_from(base, t):
    """base 를 0분으로 놓은 선형 오프셋. 자정 넘김 대응 (백엔드 SleepTimeMath 와 같은 방식)."""
    return (mins(t) - mins(base)) % (24 * 60)


def eval_suggest():
    print("\n[suggest-adjustment] 하드 제약을 지키는가")
    r = suggest_adjustment(SuggestReq(**NIGHT_REQ))
    w = NIGHT_REQ["sleepWindow"]
    draft = NIGHT_REQ["currentSleepBlock"]
    span = offset_from(w["earliestSleepStart"], w["latestSleepEnd"])
    print(f"  → 수면 {r.sleep.mainSleepStart}~{r.sleep.mainSleepEnd} / 주요식사 {r.meal.mainMealTime}")

    def case_time_format():
        for t in (r.sleep.mainSleepStart, r.sleep.mainSleepEnd, r.meal.mainMealTime):
            datetime.strptime(t, "%H:%M")  # 백엔드 LocalTime.parse 가 이걸 그대로 먹는다

    def case_within_window():
        for label, t in (("시작", r.sleep.mainSleepStart), ("종료", r.sleep.mainSleepEnd)):
            off = offset_from(w["earliestSleepStart"], t)
            assert off <= span, f"수면 {label} {t} 가 window({w['earliestSleepStart']}~{w['latestSleepEnd']}) 밖"

    def case_not_shorter():
        got = offset_from(r.sleep.mainSleepStart, r.sleep.mainSleepEnd)
        base = offset_from(draft["mainSleepStart"], draft["mainSleepEnd"])
        assert got >= base * 0.8, f"주수면이 초안({base}분)보다 크게 짧아짐: {got}분"

    def case_bounded_move():
        moved = min(abs(offset_from(draft["mainSleepStart"], r.sleep.mainSleepStart)),
                    abs(offset_from(r.sleep.mainSleepStart, draft["mainSleepStart"])))
        assert moved <= 90, f"초안에서 {moved}분 이동 — 미세 조정 범위를 넘음"

    def case_meal_not_in_night_restriction():
        mc = NIGHT_REQ["mealConstraints"]
        restricted = offset_from(mc["nightRestrictionStart"], mc["nightRestrictionEnd"])
        off = offset_from(mc["nightRestrictionStart"], r.meal.mainMealTime)
        assert not (0 < off < restricted), f"주요식사 {r.meal.mainMealTime} 가 야간 제한 구간 안"

    def case_reason_language():
        for reason in (r.sleep.reason, r.meal.reason):
            assert reason.strip(), "reason 이 비어있음"
            assert "실패" not in reason, f"금지어 '실패' 사용: {reason}"
            assert any("가" <= c <= "힣" for c in reason), f"한국어가 아님: {reason}"

    for f in (case_time_format, case_within_window, case_not_shorter,
              case_bounded_move, case_meal_not_in_night_restriction, case_reason_language):
        check(f.__name__, f)

    def case_cold_start():
        # 온보딩 직후 = 기록이 하나도 없음. 이때도 초안 수준은 나와야 한다.
        cold = {**NIGHT_REQ, "history": {}, "todayContext": None}
        c = suggest_adjustment(SuggestReq(**cold))
        datetime.strptime(c.sleep.mainSleepStart, "%H:%M")
        assert c.sleep.reason.strip(), "기록 없을 때 reason 이 비어있음"

    check("case_cold_start", case_cold_start)


# ---------- parse-schedule: 실제 근무표를 맞게 읽는가 + 얼마나 흔들리는가 ----------

RUNS = 3


def eval_schedule():
    import base64
    print(f"\n[parse-schedule] 실제 근무표에서 '{MY_ROW}' 행 (야간전담조) — {RUNS}회 반복")
    b64 = base64.b64encode(open(IMAGE, "rb").read()).decode()
    req = ScheduleReq(imageBase64=b64, myRowLabel=MY_ROW, year=2026, month=8)

    runs = []
    for i in range(RUNS):
        res = parse_schedule(req)
        assert not rejected(res), f"{i+1}회차: 읽기 실패"
        runs.append({s.date: s.shiftType for s in res.shifts})
        print(f"  {i+1}회차: {''.join(v if v != 'OFF' else '_' for v in runs[-1].values())}")

    def case_day_count():
        for i, r in enumerate(runs):
            assert len(r) == 15, f"{i+1}회차: 15일이어야 하는데 {len(r)}일"

    def case_night_only():
        # 야간전담조 행이므로 주간(D)·저녁(E)이 나오면 다른 행을 읽은 것
        for i, r in enumerate(runs):
            wrong = {d: v for d, v in r.items() if v not in ("N", "OFF")}
            assert not wrong, f"{i+1}회차: 야간전담인데 {wrong}"

    def case_stable():
        # 회차마다 값이 흔들리면 해상도 문제일 가능성이 높다(claude_client.upscale_if_small 참고).
        # 확대 도입 전 이 이미지는 15일 중 11일이 불안정했고, 확대 후 0일이 됐다.
        dates = sorted(runs[0])
        unstable = [d for d in dates if len({r.get(d) for r in runs}) > 1]
        print(f"        → {len(dates)}일 중 {len(unstable)}일이 회차마다 다름: {unstable}")
        assert not unstable, f"{len(unstable)}/{len(dates)}일 불안정 — 확대가 동작하는지 확인"

    for f in (case_day_count, case_night_only, case_stable):
        check(f.__name__, f)


SUITES = {"disruption": eval_disruption, "suggest": eval_suggest, "schedule": eval_schedule}

if __name__ == "__main__":
    only = sys.argv[1] if len(sys.argv) > 1 else None
    for name, fn in SUITES.items():
        if only in (None, name):
            fn()
    failed = [(n, m) for ok, n, m in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} PASS")
    for n, m in failed:
        print(f"  FAIL {n}: {m}")
    sys.exit(1 if failed else 0)

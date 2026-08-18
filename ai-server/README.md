# ai-server (FastAPI)

교대근무자 생활 리듬 서비스의 **AI 서버**. 스프링 백엔드가 내부망에서 호출한다.

## 원칙
- 수면·식사 **시각 계산과 모드 판정은 백엔드(코드)**가 결정론적으로 한다.
- AI는 **판단과 언어**만 담당한다.
- `/suggest-adjustment`는 규칙 기반 초안(`currentSleepBlock`)을 받아 **미세 조정한 절대시각**을 돌려준다.
  최종 clamp(`sleepWindow` 범위)는 백엔드가 한다. AI 응답이 깨지면 초안을 그대로 반환한다.
- 시각 문자열은 항상 `HH:MM`으로 검증해서 내보낸다 — 백엔드가 `LocalTime.parse`를 가드 없이 하기 때문.

## 실행
```bash
cd ai-server
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env        # ANTHROPIC_API_KEY 채우기
uvicorn app.main:app --port 8000 --reload
```

## 엔드포인트
| 메서드 | 경로 | 역할 |
|---|---|---|
| GET  | `/`                   | 헬스체크 |
| POST | `/parse-schedule`     | 근무표 사진 → 일정 (vision) |
| POST | `/parse-disruption`   | 재설계 자연어 → 이벤트 |
| POST | `/suggest-adjustment` | 초안 → 개인화 조정된 수면/식사 시각 |

요청/응답 계약은 백엔드 `domain/ai/dto/*.java` 기준.

### `/parse-schedule` 참고
실제 근무표는 **여러 사람이 든 격자**라 "내 행"을 지정해야 한다.
- `myRowLabel`(예: `"간호사7"`) — 없으면 표의 행 목록과 함께 `422 ROW_LABEL_REQUIRED`를 반환하므로,
  백엔드는 그걸로 선택 UI를 띄우고 다시 호출하면 된다.
- `year`/`month` — 선택. AI가 사진에서 직접 읽고(두 달치 표면 칸마다 다른 달로), 표에 안 적혀
  있을 때만 이 값 → 오늘 순으로 채운다. 날짜 조립(YYYY-MM-DD)은 라우터가 한다.
- `shiftType`은 표에 적힌 **코드 그대로**(D/N/주간/1조...), 쉬는 날만 `OFF`.
  표에 시간표가 없으면 `startTime`/`endTime`은 `null`(추측 안 함).
- 출력 상한은 `AI_MAX_TOKENS_VISION`(기본 8192)을 따로 쓴다. 하루당 한 칸씩 뱉으므로
  두 달치(61일)면 출력이 **약 2000토큰** — 공용 1024 상한에서는 잘려서 `shifts`가 통째로
  사라졌다(→ `IMAGE_UNREADABLE` → 백엔드 `PARSE_FAILED`). 잘린 응답은 절대 성공으로
  취급하지 않는다(`stop_reason=max_tokens`면 실패).
- 사진은 **캐시 프리픽스**로 보낸다. '행 목록 → 행 선택 후 재호출' 2단계라 같은 사진이 두 번
  나가는데, 두 번째 호출은 system+tools+사진(약 3000~4500토큰)을 0.1배로 재사용한다(TTL 5분).
  캐시 지점이 이미지 블록에 찍혀 있어야 하므로 **호출마다 달라지는 문구는 이미지 뒤에** 둘 것.

## 자체 점검
API 키 없이, 요금 없이 즉시 실행:
```bash
python -m app.claude_client                # media-type 스니핑 + 작은 이미지 확대
python -m app.routers.suggest_adjustment   # 시각 가드 + AI 실패 시 초안 폴백
```

## 응답 품질 점검 (eval)
실제 Claude를 호출해 **응답 내용**이 맞는지 본다 (요금 발생):
```bash
python eval.py              # 전체
python eval.py disruption   # disruption | suggest | schedule
```
LLM은 매번 문장이 달라 정답 비교가 불가능하므로 **불변식**만 검사한다 —
지연시간 환산(`한 시간 반`→90), 무관 입력 거부, 수면이 `sleepWindow` 밖으로 안 나감,
초안 대비 과도 이동 없음, 금지어 `실패` 없음, 근무표 3회 반복 시 결과 동일.

> **근무표 해상도 주의:** 조밀한 격자는 저해상도면 행/열이 밀린다.
> 600x355(28행) 실측 — 확대 전 15일 중 **11일** 불안정 → 확대 후 **0일**.
> `decode_image`가 긴 변을 1568px로 맞춘다(작으면 확대 `AI_MIN_IMAGE_WIDTH`,
> 크면 축소 `AI_MAX_IMAGE_EDGE`). 폰 원본(4000px)은 정확도가 더 오르지 않는데
> 이미지 토큰만 4배 든다 — 같은 표 실측 1568px **2187토큰** vs 2400px 이상 **4717토큰**.

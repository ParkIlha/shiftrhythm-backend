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
- `year`/`month` — 없으면 오늘 기준. AI는 '며칠'만 읽고 날짜 조립은 서버가 한다.
- `shiftType`은 표에 적힌 **코드 그대로**(D/N/주간/1조...), 쉬는 날만 `OFF`.
  표에 시간표가 없으면 `startTime`/`endTime`은 `null`(추측 안 함).

## 자체 점검
```bash
python -m app.claude_client   # 이미지 media-type 스니핑 확인
```

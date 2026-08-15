# ai-server (FastAPI)

교대근무자 생활 리듬 서비스의 **AI 서버**. 스프링 백엔드가 내부망에서 호출한다.

## 원칙
- 수면·식사 **시각 계산과 모드 판정은 백엔드(코드)**가 결정론적으로 한다.
- AI는 **판단과 언어**만 담당한다.
- `/suggest-adjustment`는 **절대시각을 반환하지 않는다** (분 단위 조정값만).

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
| POST | `/suggest-adjustment` | 개인화 조정폭(분) |

요청/응답 계약은 노션 **"ERD & 백엔드(AI) 로직"** B-1/B-2/B-3 기준.

## 자체 점검
```bash
python -m app.claude_client   # 이미지 media-type 스니핑 확인
```

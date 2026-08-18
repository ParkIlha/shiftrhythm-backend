# ai-server (FastAPI)

교대근무자 생활 리듬 서비스 "시차"의 AI 서버. 스프링 백엔드가 Docker 내부망에서만
호출한다(8000, 외부 노출 없음).

**이 문서는 `ai-server/`에만 적용된다.** 같은 레포의 `backend/`(Spring)와 인프라
(`docker-compose.yml`, `.github/`)는 백엔드 담당자 소유다.

## 먼저 읽을 것

- [README.md](README.md) — 원칙, 엔드포인트, 이미지 해상도·토큰 실측값. 실질적인 본문은 여기다.
- 계약 정본은 백엔드 `backend/src/main/java/**/domain/ai/dto/*.java`.
  루트 README와 DTO가 다르면 **DTO가 맞다.**

## 설계 원칙 (어기지 말 것)

- 시각 계산과 모드 판정은 **백엔드가 결정론적으로** 한다. AI는 판단·언어·비정형 입력만.
- 내보내는 시각은 반드시 `HH:MM` 형식을 검증한 뒤 송출한다.
  백엔드가 `LocalTime.parse`를 가드 없이 하고 있어서, 깨진 값이 가면 500이다.
- AI 응답이 깨지면 받은 초안(`currentSleepBlock`)을 그대로 반환한다. 예외를 위로 던지지 않는다.
  백엔드에도 폴백이 있지만 여기서 먼저 막는 게 원칙이다.
- 잘린 응답(`stop_reason == "max_tokens"`)은 **절대 성공으로 취급하지 않는다.**
- 사진 파싱 결과는 초안일 뿐이다 — 같은 사진을 재호출해도 끝쪽 칸이 미세하게 달라진다.
  사용자가 확인·수정해서 확정하는 화면이 있다는 전제로 만든다.

## 명령

```bash
# 자체점검 — API 키 불필요, 요금 없음. 푸시 전에 3개 다 돌린다.
python -m app.claude_client              # media-type 스니핑 + 이미지 크기 맞추기
python -m app.routers.parse_schedule     # 시각 못 읽었을 때 교대 프리셋 채우기
python -m app.routers.suggest_adjustment # 시각 가드 + AI 실패 시 초안 폴백

python eval.py                           # 실제 Claude 호출 — 요금 발생, 필요할 때만
uvicorn app.main:app --port 8000 --reload
```

## 함정

- **CI는 `backend/`만 본다** (`.github/workflows/backend-test.yml`의 `working-directory`).
  ai-server 변경은 자동 검증이 0이므로 자체점검 3개가 유일한 안전망이다.
- **자동화된 테스트가 없다.** 새 로직은 `claude_client`(실호출) 안이 아니라 **라우터 쪽에** 둬서
  자체점검으로 덮이게 한다. 실호출 뒤에 숨은 로직은 아무도 검증하지 못한다.
- **루트 README의 `/parse-schedule` 절은 낡았다** — 요청이 `{imageBase64}`만이고 `shiftType`이
  `DAY|EVENING|NIGHT` enum으로 적혀 있다. 실제로는 `myRowLabel`/`year`/`month`를 받고,
  shiftType은 표에 적힌 코드 그대로(D/주간/1조...) + 쉬는 날만 `OFF`. 손댈 일 있으면 같이 고칠 것.
- **프롬프트 캐시**: 호출마다 달라지는 문구는 반드시 **이미지 뒤에** 둔다.
  앞에 두면 캐시 프리픽스가 깨져서 2단계 호출(행 목록 → 행 선택 후 재호출)의 절감이 사라진다.
- `MAX_TOKENS`(1024)와 `MAX_TOKENS_VISION`(8192)은 별개다. vision에 공용 상한을 쓰면
  `shifts`가 통째로 잘려 나간다 → `IMAGE_UNREADABLE` → 백엔드 `PARSE_FAILED`.
- 모델명과 이미지 크기 상수는 `app/config.py`에서 env로 덮어쓸 수 있게 되어 있다.
  코드에 하드코딩하지 않는다.

## 작업 규칙

- `ai-server/` 밖(`backend/`, `docker-compose.yml`, `.github/`)은 담당자와 합의 없이 건드리지 않는다.
- 브랜치를 파서 PR로 머지한다. main 직접 커밋 금지 —
  **`main` push는 EC2 자동 재배포이고 롤백 수단이 없다.**
- `.env`는 읽지도, 수정하지도, 커밋하지도 않는다. 필요하면 `.env.example`을 본다.

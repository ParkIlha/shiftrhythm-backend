# shiftrhythm-backend

교대근무자 생활 리듬 서비스의 백엔드. Spring(도메인 로직) + FastAPI(AI 판단) 2개 서버로 구성된다.

## 레포 구조
```
project-root/
  backend/          Spring Boot (도메인 로직, 8080 외부 노출)
  ai-server/        FastAPI (AI 판단, 8000 내부망 전용)
  docker-compose.yml
  .env.example
```

## 통신 구조
```
[사용자] → Nginx → Spring (8080, 외부 노출)
                        ↓ (Docker 내부망, http://ai-server:8000)
                     FastAPI (8000, 외부 노출 없음)
                        ↓
                     DB (MySQL, 내부망 전용)
```
- 인프라(Docker Compose, 양쪽 Dockerfile, 네트워크, 배포)는 BE 담당
- FastAPI 내부 로직은 AI 파트 담당, BE는 엔드포인트 시그니처만 정의

## 원칙
- 수면·식사 **시각 계산과 모드 판정은 백엔드(코드)** 가 결정론적으로 한다.
- AI는 **판단과 언어**만 담당한다. `/suggest-adjustment`는 절대시각이 아니라 분 단위 조정값만 반환한다.

## 실행
```bash
cp .env.example .env   # ANTHROPIC_API_KEY 등 채우기
docker compose up --build
```
| 서비스 | 포트 | 비고 |
|---|---|---|
| backend | 8080 | 외부 노출 |
| ai-server | 8000 | 내부망 전용, 호스트 미노출 |
| db | 3306 | 내부망 전용, 호스트 미노출 |

## 배포
EC2 배포는 API 엔드포인트가 어느 정도 완성된 이후 진행 예정 (현재는 로컬 `docker compose`까지 검증 완료).

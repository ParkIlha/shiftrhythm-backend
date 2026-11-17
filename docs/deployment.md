# 배포 운영 메모

EC2에 배포한다. 레포에 별도의 프로덕션 전용 설정(예: `docker-compose.prod.yml`, nginx, ECR)은 없고,
로컬 개발과 동일한 `docker-compose.yml`을 EC2 위에서 그대로 띄운다.

- **인프라**: EC2 인스턴스 하나에 `docker compose`로 `backend`/`ai-server`/`db`(MySQL) 컨테이너 3개를 띄운다.
  RDS 등 외부 관리형 DB는 쓰지 않는다.
- **CD**: `main`에 push되면 `.github/workflows/backend-test.yml`의 `deploy` job이 테스트 통과 후 자동으로
  EC2에 SSH 접속해 `git pull` → `docker compose up -d --build`를 실행한다. GitHub Secrets에
  `EC2_HOST`/`EC2_USER`/`EC2_SSH_KEY`가 등록돼 있어야 동작한다.
- **포트/노출**: `backend`만 컨테이너 `0.0.0.0:8080`으로 호스트에 노출한다. `ai-server`는 포트 매핑이 없어
  같은 docker 네트워크 안에서 `backend`가 `http://ai-server:8000`으로만 접근할 수 있다(외부 직접 접근 불가).
- **외부 공개**: nginx 리버스 프록시는 없다. EC2 위에서 `cloudflared tunnel --url http://localhost:8080`를
  docker compose와 별개의 백그라운드 프로세스로 실행해, 요청 시 발급되는 `*.trycloudflare.com` 주소로
  8080을 외부에 공개한다. 이 프로세스가 재시작되면 URL이 바뀐다. CD와는 무관하게 별도로 관리해야 한다.
- **데모 데이터**: `backend/src/main/resources/db/seed_demo.sql`은 Flyway 마이그레이션이 아니라 수동 시드라
  CD가 자동으로 돌리지 않는다. 시연 전엔 EC2에서 직접
  `docker compose exec -T db mysql --default-character-set=utf8mb4 -u root -proot shiftrhythm < backend/src/main/resources/db/seed_demo.sql`로
  다시 넣어줘야 한다(날짜가 `CURDATE()` 기준 상대값이라 실행 시점 기준 "오늘"로 리셋된다).
  `--default-character-set=utf8mb4`를 빠뜨리면 mysql 클라이언트가 접속 charset을 기본값(환경에 따라
  latin1 등)으로 잡아서 한글 리터럴(`ai_reason` 등)이 깨져 저장된다.

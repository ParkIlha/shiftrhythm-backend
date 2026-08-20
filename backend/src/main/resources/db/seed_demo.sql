-- 데모용 시드 데이터.
--
-- Flyway 가 자동 실행하지 않도록 migration/ 밖에 둔다. 데모 전에 수동으로 돌린다:
--   docker compose exec -T db mysql -uroot -proot shiftrhythm < backend/src/main/resources/db/seed_demo.sql
--
-- 날짜는 CURDATE() 기준 상대값이라 언제 돌려도 "오늘 = 야간 3일차"가 된다.
-- 시나리오 (D-6 ~ 오늘):
--   OFF(회복) → DAY → DAY → OFF(리듬전환) → NIGHT 1일차 → NIGHT 2일차 → NIGHT 3일차(오늘)
-- 야간이 누적되며 컨디션·수면만족이 떨어지고 잠들기까지 시간이 늘어난다.
-- D-1 에 "퇴근 2시간 지연" 재계획이 1건 들어있어 리포트의 재계획 로그가 비지 않는다.

SET @uid = 1;

DELETE FROM routine_result WHERE user_profile_id = @uid;
DELETE FROM daily_check_in WHERE user_profile_id = @uid;
DELETE FROM shift            WHERE user_profile_id = @uid;
DELETE FROM shift_type_default WHERE user_profile_id = @uid;
DELETE FROM user_profile     WHERE id = @uid;

-- ── 프로필 ─────────────────────────────────────────────────────────
-- 통근 30분 / 준비 30분 / 목표수면 7시간 / 근무 중 휴식 가능 30분 / 균형 선호
INSERT INTO user_profile
  (id, name, commute_minutes, prep_minutes, target_sleep_minutes, nap_available, nap_available_minutes, rhythm_preference)
VALUES (@uid, '데모', 30, 30, 420, TRUE, 30, 'BALANCED');

INSERT INTO shift_type_default (user_profile_id, shift_type, default_start_time, default_end_time) VALUES
  (@uid, 'DAY',     '07:00:00', '15:00:00'),
  (@uid, 'EVENING', '14:00:00', '22:00:00'),
  (@uid, 'NIGHT',   '22:00:00', '07:00:00');

-- ── 근무표 (D-6 ~ D+7) ────────────────────────────────────────────
-- 오늘이 NIGHT 3일차. 내일도 NIGHT 이어야 오늘이 NIGHT 모드로 판정된다.
INSERT INTO shift (user_profile_id, date, shift_type) VALUES
  (@uid, CURDATE() - INTERVAL 6 DAY, 'OFF'),
  (@uid, CURDATE() - INTERVAL 5 DAY, 'DAY'),
  (@uid, CURDATE() - INTERVAL 4 DAY, 'DAY'),
  (@uid, CURDATE() - INTERVAL 3 DAY, 'OFF'),
  (@uid, CURDATE() - INTERVAL 2 DAY, 'NIGHT'),
  (@uid, CURDATE() - INTERVAL 1 DAY, 'NIGHT'),
  (@uid, CURDATE(),                  'NIGHT'),
  (@uid, CURDATE() + INTERVAL 1 DAY, 'NIGHT'),
  (@uid, CURDATE() + INTERVAL 2 DAY, 'OFF'),
  (@uid, CURDATE() + INTERVAL 3 DAY, 'OFF'),
  (@uid, CURDATE() + INTERVAL 4 DAY, 'DAY'),
  (@uid, CURDATE() + INTERVAL 5 DAY, 'DAY'),
  (@uid, CURDATE() + INTERVAL 6 DAY, 'DAY'),
  (@uid, CURDATE() + INTERVAL 7 DAY, 'OFF');

-- ── 확정된 루틴 (D-6 ~ 오늘) ──────────────────────────────────────
-- NIGHT 은 주수면(4.5h) + 보조수면(2.5h) 분할. DAY/OFF 는 단일 블록.
-- sleep_start/sleep_end 등이 V4 마이그레이션으로 DATETIME이 됐으므로, 각 행의 date를 기준으로
-- TIMESTAMP(date, time)으로 결합한다. end가 start보다 이르면(자정을 넘긴 것) date+1을 붙인다
-- (V4의 백필 규칙과 동일).
INSERT INTO routine_result
  (user_profile_id, date, version, is_current, replan_reason, mode,
   sleep_start, sleep_end, supplementary_sleep_start, supplementary_sleep_end, nap_minutes,
   meal_times, ai_reason, ai_updated_at)
VALUES
  -- D-6  야간 끝난 뒤 회복 휴무
  (@uid, CURDATE() - INTERVAL 6 DAY, 1, TRUE, NULL, 'OFF_RECOVERY',
   TIMESTAMP(CURDATE() - INTERVAL 6 DAY, '01:30:00'), TIMESTAMP(CURDATE() - INTERVAL 6 DAY, '10:00:00'), NULL, NULL, NULL,
   '{"mainMeal":"12:00:00","subMeal":"18:00:00","snackTime":null,"bigMealCutoff":"00:00:00","nightRestrictionStart":"00:00:00","nightRestrictionEnd":"06:00:00"}',
   '야간 근무가 끝난 첫 휴무라 부족한 잠을 조금 더 채우도록 배치했어요.', NULL),

  -- D-5  주간
  (@uid, CURDATE() - INTERVAL 5 DAY, 1, TRUE, NULL, 'DAY',
   TIMESTAMP(CURDATE() - INTERVAL 5 DAY, '23:00:00'), TIMESTAMP(CURDATE() - INTERVAL 4 DAY, '06:00:00'), NULL, NULL, NULL,
   '{"mainMeal":"12:00:00","subMeal":"18:00:00","snackTime":null,"bigMealCutoff":"21:30:00","nightRestrictionStart":"00:00:00","nightRestrictionEnd":"06:00:00"}',
   NULL, NULL),

  -- D-4  주간
  (@uid, CURDATE() - INTERVAL 4 DAY, 1, TRUE, NULL, 'DAY',
   TIMESTAMP(CURDATE() - INTERVAL 4 DAY, '23:00:00'), TIMESTAMP(CURDATE() - INTERVAL 3 DAY, '06:00:00'), NULL, NULL, NULL,
   '{"mainMeal":"12:00:00","subMeal":"18:00:00","snackTime":null,"bigMealCutoff":"21:30:00","nightRestrictionStart":"00:00:00","nightRestrictionEnd":"06:00:00"}',
   NULL, NULL),

  -- D-3  주간에서 야간으로 넘어가는 휴무 — 취침을 뒤로 밀기 시작
  (@uid, CURDATE() - INTERVAL 3 DAY, 1, TRUE, NULL, 'OFF_RHYTHM_SHIFT',
   TIMESTAMP(CURDATE() - INTERVAL 3 DAY, '02:00:00'), TIMESTAMP(CURDATE() - INTERVAL 3 DAY, '09:30:00'), NULL, NULL, NULL,
   '{"mainMeal":"12:00:00","subMeal":"18:30:00","snackTime":null,"bigMealCutoff":"00:30:00","nightRestrictionStart":"00:00:00","nightRestrictionEnd":"06:00:00"}',
   '내일부터 야간이라 취침을 조금 늦춰 리듬을 미리 옮겼어요.', NULL),

  -- D-2  야간 1일차
  (@uid, CURDATE() - INTERVAL 2 DAY, 1, TRUE, NULL, 'NIGHT',
   TIMESTAMP(CURDATE() - INTERVAL 2 DAY, '07:30:00'), TIMESTAMP(CURDATE() - INTERVAL 2 DAY, '12:00:00'),
   TIMESTAMP(CURDATE() - INTERVAL 2 DAY, '18:30:00'), TIMESTAMP(CURDATE() - INTERVAL 2 DAY, '21:00:00'), NULL,
   '{"mainMeal":"13:00:00","subMeal":"17:00:00","snackTime":"22:30:00","bigMealCutoff":"06:00:00","nightRestrictionStart":"00:00:00","nightRestrictionEnd":"06:00:00"}',
   NULL, NULL),

  -- D-1  야간 2일차 · v1(원래 계획) → 퇴근이 밀려 v2 로 재설계됨
  (@uid, CURDATE() - INTERVAL 1 DAY, 1, FALSE, NULL, 'NIGHT',
   TIMESTAMP(CURDATE() - INTERVAL 1 DAY, '07:30:00'), TIMESTAMP(CURDATE() - INTERVAL 1 DAY, '12:00:00'),
   TIMESTAMP(CURDATE() - INTERVAL 1 DAY, '18:30:00'), TIMESTAMP(CURDATE() - INTERVAL 1 DAY, '21:00:00'), NULL,
   '{"mainMeal":"13:00:00","subMeal":"17:00:00","snackTime":"22:30:00","bigMealCutoff":"06:00:00","nightRestrictionStart":"00:00:00","nightRestrictionEnd":"06:00:00"}',
   NULL, NULL),
  (@uid, CURDATE() - INTERVAL 1 DAY, 2, TRUE, 'LATE_CLOCKOUT', 'NIGHT',
   TIMESTAMP(CURDATE() - INTERVAL 1 DAY, '09:30:00'), TIMESTAMP(CURDATE() - INTERVAL 1 DAY, '13:30:00'),
   TIMESTAMP(CURDATE() - INTERVAL 1 DAY, '18:30:00'), TIMESTAMP(CURDATE() - INTERVAL 1 DAY, '21:00:00'), 30,
   '{"mainMeal":"14:00:00","subMeal":"17:30:00","snackTime":"22:30:00","bigMealCutoff":"08:00:00","nightRestrictionStart":"00:00:00","nightRestrictionEnd":"06:00:00"}',
   '퇴근이 2시간 늦어져 주수면을 그만큼 미뤘어요. 줄어든 만큼은 근무 중 짧은 낮잠으로 채웁니다.',
   NOW() - INTERVAL 1 DAY),

  -- D0  오늘 · 야간 3일차 — 재설계 데모는 여기서 일어난다
  (@uid, CURDATE(), 1, TRUE, NULL, 'NIGHT',
   TIMESTAMP(CURDATE(), '07:30:00'), TIMESTAMP(CURDATE(), '12:00:00'),
   TIMESTAMP(CURDATE(), '18:30:00'), TIMESTAMP(CURDATE(), '21:00:00'), NULL,
   '{"mainMeal":"13:00:00","subMeal":"17:00:00","snackTime":"22:30:00","bigMealCutoff":"06:00:00","nightRestrictionStart":"00:00:00","nightRestrictionEnd":"06:00:00"}',
   '야간 3일차라 잠들기까지 걸리는 시간이 길어지고 있어요. 주수면을 조금 앞당겼습니다.',
   NOW() - INTERVAL 2 HOUR);

-- ── 체크인 ────────────────────────────────────────────────────────
-- 야간이 쌓이며 컨디션·만족도는 내려가고 잠들기까지 시간과 야간허기는 올라간다.
-- suggest-adjustment 가 이 추세를 읽어 개인화 제안을 낸다.
-- night_hunger_score 는 NIGHT/EVENING 근무일에만 채운다.
INSERT INTO daily_check_in
  (user_profile_id, date, condition_score, sleep_satisfaction, sleep_latency_minutes,
   actual_clock_out, night_hunger_score, updated_at)
VALUES
  (@uid, CURDATE() - INTERVAL 6 DAY, 4, 4, 20, NULL,        NULL, NOW() - INTERVAL 6 DAY),
  (@uid, CURDATE() - INTERVAL 5 DAY, 4, 4, 25, '15:05:00',  NULL, NOW() - INTERVAL 5 DAY),
  (@uid, CURDATE() - INTERVAL 4 DAY, 3, 3, 30, '15:20:00',  NULL, NOW() - INTERVAL 4 DAY),
  (@uid, CURDATE() - INTERVAL 3 DAY, 4, 4, 25, NULL,        NULL, NOW() - INTERVAL 3 DAY),
  (@uid, CURDATE() - INTERVAL 2 DAY, 3, 3, 40, '07:10:00',  3,    NOW() - INTERVAL 2 DAY),
  (@uid, CURDATE() - INTERVAL 1 DAY, 2, 2, 50, '09:05:00',  4,    NOW() - INTERVAL 1 DAY),
  -- 오늘은 기상 체크인만. 아직 퇴근 전이라 actual_clock_out 은 비어 있다.
  (@uid, CURDATE(),                  2, 2, 55, NULL,        NULL, NOW() - INTERVAL 3 HOUR);

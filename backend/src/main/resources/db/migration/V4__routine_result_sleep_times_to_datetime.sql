-- sleep_start/sleep_end/supplementary_sleep_*/adjusted_shift_end_time을 TIME -> DATETIME으로 전환.
-- 지금까지는 시각만 저장돼 있어서(자정을 넘기는 근무의 경우) 어느 캘린더 날짜에 속하는지 알 수 없었다.
-- 백필 규칙: 각 컬럼을 `date`와 결합하되, end 계열 컬럼이 start 계열보다 이르면(자정을 넘긴 것) 다음날로 붙인다.

ALTER TABLE `routine_result`
    ADD COLUMN `sleep_start_dt`               DATETIME NULL AFTER `sleep_start`,
    ADD COLUMN `sleep_end_dt`                 DATETIME NULL AFTER `sleep_end`,
    ADD COLUMN `supplementary_sleep_start_dt` DATETIME NULL AFTER `supplementary_sleep_start`,
    ADD COLUMN `supplementary_sleep_end_dt`   DATETIME NULL AFTER `supplementary_sleep_end`,
    ADD COLUMN `adjusted_shift_end_time_dt`   DATETIME NULL AFTER `adjusted_shift_end_time`;

UPDATE `routine_result`
SET `sleep_start_dt` = TIMESTAMP(`date`, `sleep_start`);

UPDATE `routine_result`
SET `sleep_end_dt` = IF(`sleep_end` < `sleep_start`,
                        TIMESTAMP(DATE_ADD(`date`, INTERVAL 1 DAY), `sleep_end`),
                        TIMESTAMP(`date`, `sleep_end`));

UPDATE `routine_result`
SET `supplementary_sleep_start_dt` = TIMESTAMP(`date`, `supplementary_sleep_start`)
WHERE `supplementary_sleep_start` IS NOT NULL;

UPDATE `routine_result`
SET `supplementary_sleep_end_dt` = IF(`supplementary_sleep_end` < `supplementary_sleep_start`,
                                      TIMESTAMP(DATE_ADD(`date`, INTERVAL 1 DAY), `supplementary_sleep_end`),
                                      TIMESTAMP(`date`, `supplementary_sleep_end`))
WHERE `supplementary_sleep_end` IS NOT NULL;

-- adjusted_shift_end_time은 근무 종료시각이라 sleep_start보다 이르면 자정을 넘긴 것으로 본다.
UPDATE `routine_result`
SET `adjusted_shift_end_time_dt` = IF(`adjusted_shift_end_time` < `sleep_start`,
                                      TIMESTAMP(DATE_ADD(`date`, INTERVAL 1 DAY), `adjusted_shift_end_time`),
                                      TIMESTAMP(`date`, `adjusted_shift_end_time`))
WHERE `adjusted_shift_end_time` IS NOT NULL;

ALTER TABLE `routine_result`
    DROP COLUMN `sleep_start`,
    DROP COLUMN `sleep_end`,
    DROP COLUMN `supplementary_sleep_start`,
    DROP COLUMN `supplementary_sleep_end`,
    DROP COLUMN `adjusted_shift_end_time`;

ALTER TABLE `routine_result`
    CHANGE COLUMN `sleep_start_dt` `sleep_start` DATETIME NOT NULL,
    CHANGE COLUMN `sleep_end_dt` `sleep_end` DATETIME NOT NULL,
    CHANGE COLUMN `supplementary_sleep_start_dt` `supplementary_sleep_start` DATETIME NULL,
    CHANGE COLUMN `supplementary_sleep_end_dt` `supplementary_sleep_end` DATETIME NULL,
    CHANGE COLUMN `adjusted_shift_end_time_dt` `adjusted_shift_end_time` DATETIME NULL
        COMMENT '재설계로 근무 종료시각이 실제로 바뀐 경우에만 채워짐(SHIFT_END_DELAY/SHIFT_ADDED). 그 외엔 NULL.';

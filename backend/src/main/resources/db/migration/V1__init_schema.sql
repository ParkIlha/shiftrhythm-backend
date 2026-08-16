CREATE TABLE `user_profile` (
    `id`                    BIGINT      NOT NULL AUTO_INCREMENT,
    `commute_minutes`       INT         NOT NULL,
    `prep_minutes`          INT         NOT NULL,
    `target_sleep_minutes`  INT         NOT NULL DEFAULT 420,
    `nap_available`         BOOLEAN     NOT NULL,
    `nap_available_minutes` INT         NULL,
    `rhythm_preference`     VARCHAR(20) NOT NULL COMMENT 'ENUM: RHYTHM_LEAN / BALANCED / DAY_LEAN',
    PRIMARY KEY (`id`)
);

CREATE TABLE `shift_type_default` (
    `id`                 BIGINT      NOT NULL AUTO_INCREMENT,
    `user_profile_id`    BIGINT      NOT NULL,
    `shift_type`         VARCHAR(20) NOT NULL COMMENT 'ENUM: DAY / EVENING / NIGHT',
    `default_start_time` TIME        NOT NULL,
    `default_end_time`   TIME        NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `UK_SHIFTTYPEDEFAULT_PROFILE_TYPE` UNIQUE (`user_profile_id`, `shift_type`),
    CONSTRAINT `FK_SHIFTTYPEDEFAULT_USERPROFILE` FOREIGN KEY (`user_profile_id`) REFERENCES `user_profile` (`id`)
);

CREATE TABLE `shift` (
    `id`                   BIGINT      NOT NULL AUTO_INCREMENT,
    `user_profile_id`      BIGINT      NOT NULL,
    `date`                 DATE        NOT NULL COMMENT '근무 시작일 기준(자정 넘겨도 시작일로 저장)',
    `shift_type`           VARCHAR(20) NOT NULL COMMENT 'ENUM: DAY/EVENING/NIGHT/OFF',
    `start_time_override`  TIME        NULL,
    `end_time_override`    TIME        NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `UK_SHIFT_PROFILE_DATE` UNIQUE (`user_profile_id`, `date`),
    CONSTRAINT `FK_SHIFT_USERPROFILE` FOREIGN KEY (`user_profile_id`) REFERENCES `user_profile` (`id`)
);

CREATE TABLE `daily_check_in` (
    `id`                      BIGINT      NOT NULL AUTO_INCREMENT,
    `user_profile_id`         BIGINT      NOT NULL,
    `date`                    DATE        NOT NULL,
    `condition_score`         INT         NULL COMMENT '1~5점',
    `sleep_satisfaction`      INT         NULL COMMENT '1~5점',
    `sleep_latency_minutes`   INT         NULL,
    `actual_clock_out`        TIME        NULL,
    `night_hunger_score`      INT         NULL COMMENT '1~5점, NIGHT/EVENING 근무일에만 프론트가 노출',
    `updated_at`              DATETIME    NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `UK_DAILYCHECKIN_PROFILE_DATE` UNIQUE (`user_profile_id`, `date`),
    CONSTRAINT `FK_DAILYCHECKIN_USERPROFILE` FOREIGN KEY (`user_profile_id`) REFERENCES `user_profile` (`id`)
);

CREATE TABLE `routine_result` (
    `id`                          BIGINT      NOT NULL AUTO_INCREMENT,
    `user_profile_id`             BIGINT      NOT NULL,
    `date`                        DATE        NOT NULL,
    `version`                     INT         NOT NULL DEFAULT 1,
    `is_current`                  BOOLEAN     NOT NULL,
    `replan_reason`               VARCHAR(20) NULL COMMENT 'ENUM: LATE_CLOCKOUT/EARLY_CLOCKOUT/SHIFT_CHANGE/PERSONAL_SCHEDULE/OTHER, version 1은 null',
    `mode`                        VARCHAR(30) NOT NULL COMMENT 'ENUM: DAY/EVENING/NIGHT/SHIFT_TRANSITION/OFF_RHYTHM_MAINTAIN/OFF_RHYTHM_SHIFT/OFF_RECOVERY',
    `sleep_start`                 TIME        NOT NULL,
    `sleep_end`                   TIME        NOT NULL,
    `supplementary_sleep_start`   TIME        NULL,
    `supplementary_sleep_end`     TIME        NULL,
    `nap_minutes`                 INT         NULL,
    `meal_times`                  JSON        NOT NULL,
    `ai_reason`                   TEXT        NULL,
    `ai_updated_at`               DATETIME    NULL COMMENT 'DailyCheckIn.updated_at과 비교해서 AI 재호출 여부 판단',
    PRIMARY KEY (`id`),
    CONSTRAINT `UK_ROUTINERESULT_PROFILE_DATE_VERSION` UNIQUE (`user_profile_id`, `date`, `version`),
    CONSTRAINT `FK_ROUTINERESULT_USERPROFILE` FOREIGN KEY (`user_profile_id`) REFERENCES `user_profile` (`id`)
);

CREATE INDEX `IDX_ROUTINERESULT_PROFILE_DATE_CURRENT` ON `routine_result` (`user_profile_id`, `date`, `is_current`);

ALTER TABLE `routine_result`
    ADD COLUMN `adjusted_shift_end_time` TIME NULL
        COMMENT '재설계로 근무 종료시각이 실제로 바뀐 경우에만 채워짐(SHIFT_END_DELAY/SHIFT_ADDED). 그 외엔 NULL.'
        AFTER `nap_minutes`;

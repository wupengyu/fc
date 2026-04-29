USE wechat_msg;

CREATE TABLE IF NOT EXISTS t_raw_correction_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'primary key',
    raw_id BIGINT NOT NULL COMMENT '关联原始报单ID',
    msg_id VARCHAR(128) NULL COMMENT '微信消息ID',
    received_at DATETIME NULL COMMENT '原始消息接收时间',
    lottery_category VARCHAR(32) NULL COMMENT '彩种类别',
    game_type VARCHAR(32) NULL COMMENT '游戏类型',
    play_type VARCHAR(32) NULL COMMENT '玩法',
    number_zone VARCHAR(32) NULL COMMENT '号码区域',
    target_number VARCHAR(32) NULL COMMENT '目标号码',
    correct_bet INT NULL COMMENT '人工确认正确注数',
    original_text LONGTEXT NULL COMMENT '原始微信消息',
    corrected_text LONGTEXT NULL COMMENT '人工修正文案',
    parse_text LONGTEXT NULL COMMENT '最终送AI解析文本',
    result_status VARCHAR(32) NULL COMMENT '重解析结果状态',
    success_item_count INT NOT NULL DEFAULT 0 COMMENT '成功条数',
    skip_item_count INT NOT NULL DEFAULT 0 COMMENT '跳过条数',
    fail_item_count INT NOT NULL DEFAULT 0 COMMENT '失败条数',
    result_summary TEXT NULL COMMENT '重解析结果摘要',
    result_json LONGTEXT NULL COMMENT '重解析结果明细JSON',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_t_raw_correction_record_raw_id (raw_id),
    KEY idx_t_raw_correction_record_received_at (received_at),
    KEY idx_t_raw_correction_record_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工校正重解析留痕';

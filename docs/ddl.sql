-- 福彩/体彩报单统计系统 DDL
-- 数据库：wechat_msg

CREATE TABLE IF NOT EXISTS t_order_raw (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  msg_id            VARCHAR(64)  NULL COMMENT '微信消息ID(可空)',
  fingerprint       VARCHAR(64)  NOT NULL COMMENT '幂等指纹sha256',
  source            VARCHAR(32)  NOT NULL DEFAULT 'WECHAT' COMMENT 'WECHAT/API',
  from_wxid         VARCHAR(64)  NULL COMMENT '群wxid',
  sender_wxid       VARCHAR(64)  NULL COMMENT '发送人wxid',
  raw_text          TEXT         NOT NULL COMMENT '原始文本',
  received_at       DATETIME(3)  NOT NULL,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  UNIQUE KEY uk_fingerprint (fingerprint),
  KEY idx_received_at (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原始报单';

CREATE TABLE IF NOT EXISTS t_order_parse_batch (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  raw_id        BIGINT       NOT NULL,
  parse_version VARCHAR(16)  NOT NULL DEFAULT 'v1',
  ai_model      VARCHAR(32)  NULL,
  ai_latency_ms INT          NULL,
  parse_status  TINYINT      NOT NULL COMMENT '1成功 2部分成功 3失败',
  parse_msg     VARCHAR(512) NULL,
  is_effective  TINYINT      NOT NULL DEFAULT 1 COMMENT '1有效 0无效',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  KEY idx_raw_id (raw_id),
  KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI解析批次';

-- v3: 添加AI原始响应JSON字段
ALTER TABLE t_order_parse_batch
  ADD COLUMN ai_response_body MEDIUMTEXT NULL COMMENT 'AI原始响应JSON' AFTER ai_latency_ms;

CREATE TABLE IF NOT EXISTS t_order_item (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  raw_id          BIGINT       NOT NULL,
  batch_id        BIGINT       NOT NULL,
  item_no         INT          NOT NULL COMMENT '同raw下的序号',

  lottery_category VARCHAR(8)  NOT NULL COMMENT 'FC/TC',
  game_type       VARCHAR(32)  NOT NULL COMMENT 'SSQ/DLT/3D/P3/...',
  play_type       VARCHAR(32)  NOT NULL DEFAULT 'ALL' COMMENT '玩法',

  issue_no        VARCHAR(32)  NULL COMMENT '期号(可空)',
  issue_key       VARCHAR(32)  NOT NULL COMMENT '统计期维度',

  bet_count       INT          NOT NULL DEFAULT 0,
  group_count     INT          NOT NULL DEFAULT 0,
  multiple        INT          NOT NULL DEFAULT 1,

  total_amount    DECIMAL(14,2) NOT NULL DEFAULT 0.00,
  amount_alloc_mode VARCHAR(8)  NOT NULL DEFAULT 'SPLIT',

  raw_text        TEXT         NOT NULL,
  stat_applied    TINYINT      NOT NULL DEFAULT 0 COMMENT '0未入统计 1已入统计',

  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  UNIQUE KEY uk_batch_item (batch_id, item_no),
  KEY idx_raw (raw_id),
  KEY idx_dim (lottery_category, game_type, issue_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='拆分投注项';

CREATE TABLE IF NOT EXISTS t_order_item_number (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  item_id       BIGINT       NOT NULL,
  number_zone   VARCHAR(16)  NOT NULL DEFAULT 'MAIN' COMMENT 'MAIN/RED/BLUE/FRONT/BACK/POSITION',
  number        VARCHAR(32)  NOT NULL COMMENT '号码',
  amount_alloc  DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '分摊金额',

  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  KEY idx_item (item_id),
  KEY idx_number (number, number_zone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='号码粒度明细';

CREATE TABLE IF NOT EXISTS t_ai_parse_result (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  raw_id        BIGINT       NOT NULL COMMENT 'FK to t_order_raw',
  batch_id      BIGINT       NULL     COMMENT 'FK to t_order_parse_batch',
  item_index    INT          NOT NULL COMMENT 'AI返回的index',
  valid         TINYINT      NOT NULL DEFAULT 0,
  status        VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/SKIP/FAIL',
  reason        VARCHAR(512) NULL     COMMENT 'SKIP/FAIL原因',

  category      VARCHAR(8)   NULL COMMENT 'FC/TC',
  game          VARCHAR(32)  NULL COMMENT '3D/P3/P5/SSQ/DLT/QLQ',
  play          VARCHAR(32)  NULL COMMENT '直选/复式/独胆',
  zone          VARCHAR(16)  NULL COMMENT 'MAIN/RED/BLUE/FRONT/BACK',
  numbers       TEXT         NULL COMMENT 'JSON数组，如["375","572"]',
  bet           INT          NOT NULL DEFAULT 0 COMMENT '注数',
  multiple      INT          NOT NULL DEFAULT 1 COMMENT '倍数',
  amount        DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '金额(元)',

  issue_key     VARCHAR(32)  NULL COMMENT '统计期维度 YYYY-MM-DD',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  KEY idx_raw_id (raw_id),
  KEY idx_issue_key (issue_key),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI解析结果明细';

CREATE TABLE IF NOT EXISTS t_number_stats (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  lottery_category VARCHAR(8)  NOT NULL,
  game_type       VARCHAR(32) NOT NULL,
  play_type       VARCHAR(32) NOT NULL,
  issue_key       VARCHAR(32) NOT NULL,

  number_zone     VARCHAR(16) NOT NULL DEFAULT 'MAIN',
  number          VARCHAR(32) NOT NULL,

  order_count     INT         NOT NULL DEFAULT 0 COMMENT '出现次数',
  sum_amount      DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '累计金额',
  last_updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

  UNIQUE KEY uk_stat (lottery_category, game_type, play_type, issue_key, number_zone, number),
  KEY idx_updated (last_updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='号码统计汇总';

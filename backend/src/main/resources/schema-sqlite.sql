-- SQLite 版 Schema（Stage SQLite-1）。
-- 由 sqlite profile 的 spring.sql.init 加载（classpath:schema-sqlite.sql）。
-- 与 MySQL 的 schema.sql 并列存在，两者不会同时加载：默认 profile 走 schema.sql，sqlite profile 走本文件。
--
-- 与 MySQL 版的差异只限方言：
--   * AUTO_INCREMENT -> INTEGER PRIMARY KEY AUTOINCREMENT
--   * 去掉 ENGINE / CHARSET
--   * MEDIUMTEXT / VARCHAR(n) / CHAR(n) -> TEXT
--   * TINYINT(1) -> INTEGER
--   * DATETIME -> TEXT（统一存 'YYYY-MM-DD HH:MM:SS'，由 CURRENT_TIMESTAMP 写入）
--   * 去掉 ON UPDATE CURRENT_TIMESTAMP（SQLite 不支持；由 SQLite-2 的应用层或触发器补偿）
--   * 行内 INDEX / UNIQUE KEY 拆成独立的 CREATE INDEX / CREATE UNIQUE INDEX
--   * 去掉只针对旧库的 ALTER TABLE ... MODIFY COLUMN
--
-- 列名、唯一键组合、索引覆盖范围、字段可空性与默认值均与 MySQL 版保持一致。
-- 唯一索引的列组合必须与后续 ON CONFLICT 的目标列完全对应，不要擅自合并或拆分。
-- 全部语句幂等，可重复执行。

CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL,
  password TEXT NOT NULL,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS conversation_message (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  conversation_id TEXT NOT NULL,
  role TEXT NOT NULL,
  content TEXT,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tool_call_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  conversation_id TEXT,
  tool_name TEXT NOT NULL,
  arguments TEXT,
  result TEXT,
  success INTEGER NOT NULL DEFAULT 1,
  elapsed_ms INTEGER,
  high_risk INTEGER NOT NULL DEFAULT 0,
  approved INTEGER NOT NULL DEFAULT 1,
  turn_index INTEGER NULL,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_model_config (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  provider TEXT NOT NULL,
  base_url TEXT NOT NULL,
  api_key TEXT NOT NULL,
  model_name TEXT NOT NULL,
  context_window_tokens INTEGER,
  default_model INTEGER NOT NULL DEFAULT 0,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS conversation (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  conversation_id TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '新对话',
  message_count INTEGER NOT NULL DEFAULT 0,
  plan_json TEXT NULL,
  workspace_id INTEGER NULL,
  permission_mode TEXT NOT NULL DEFAULT 'ASK',
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS workspace (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  bound_path TEXT NOT NULL,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_memory (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  category TEXT NOT NULL,
  mem_key TEXT NOT NULL,
  content TEXT NOT NULL,
  source TEXT NOT NULL DEFAULT 'explicit',
  conversation_id TEXT NULL,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS mcp_user_policy (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  server_name TEXT NOT NULL,
  tool_name TEXT NOT NULL DEFAULT '',
  effect TEXT NOT NULL,
  definition_hash TEXT NULL,
  updated_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS mcp_server_config (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  -- COLLATE NOCASE 对齐 MySQL 的 utf8mb4_0900_ai_ci 在「大小写」这一维的行为：
  -- 服务名查找与 uk_owner_name 唯一索引都按 ASCII 大小写不敏感处理。
  -- 重音折叠（café / cafe）NOCASE 不做，这一点与 MySQL 仍有差异，已在 SQLite-2 报告中记录。
  name TEXT NOT NULL COLLATE NOCASE,
  command TEXT NULL,
  args_json TEXT NULL,
  env_json TEXT NULL,
  disabled_tools_json TEXT NULL,
  trust TEXT NOT NULL DEFAULT 'untrusted',
  connect_timeout_seconds INTEGER NOT NULL DEFAULT 0,
  max_result_chars INTEGER NOT NULL DEFAULT 0,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_by INTEGER NOT NULL DEFAULT 0,
  transport TEXT NOT NULL DEFAULT 'stdio',
  base_url TEXT NULL,
  endpoint_path TEXT NULL,
  auth_type TEXT NOT NULL DEFAULT 'none',
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS mcp_server_credential (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  server_config_id INTEGER NOT NULL,
  ciphertext TEXT NOT NULL,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS agent_turn (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id TEXT,
  user_id INTEGER,
  turn_index INTEGER NOT NULL,
  phase TEXT NOT NULL,
  model_name TEXT,
  system_prompt_sha256 TEXT,
  system_dynamic_sha256 TEXT NULL,
  tools_sha256 TEXT,
  compact_mode TEXT,
  tokens_before_compact_est INTEGER,
  tokens_after_compact_est INTEGER,
  compacted_message_count INTEGER,
  input_tokens INTEGER NULL,
  output_tokens INTEGER NULL,
  est_tokens_system INTEGER,
  est_tokens_injected INTEGER NULL,
  est_tokens_tools INTEGER,
  est_tokens_history INTEGER,
  est_tokens_tool_results INTEGER,
  end_reason TEXT,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS prompt_snapshot (
  sha256 TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  content TEXT NOT NULL,
  first_seen_at TEXT DEFAULT CURRENT_TIMESTAMP
);

-- ===== 唯一索引（列组合与 MySQL 版 UNIQUE KEY 一致）=====
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_username ON users(username);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_conv ON conversation(user_id, conversation_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_bound_path ON workspace(user_id, bound_path);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_cat_key ON user_memory(user_id, category, mem_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_server_tool ON mcp_user_policy(user_id, server_name, tool_name);
CREATE UNIQUE INDEX IF NOT EXISTS uk_owner_name ON mcp_server_config(created_by, name);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_server ON mcp_server_credential(user_id, server_config_id);

-- ===== 普通索引（覆盖范围与 MySQL 版一致）=====
CREATE INDEX IF NOT EXISTS idx_conv ON conversation_message(user_id, conversation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_user_time ON tool_call_log(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_tool ON tool_call_log(tool_name);
CREATE INDEX IF NOT EXISTS idx_tool_call_conv_turn ON tool_call_log(conversation_id, turn_index);
CREATE INDEX IF NOT EXISTS idx_user ON user_model_config(user_id);
CREATE INDEX IF NOT EXISTS idx_user_updated ON conversation(user_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_conv_workspace ON conversation(workspace_id);
CREATE INDEX IF NOT EXISTS idx_ws_user ON workspace(user_id);
CREATE INDEX IF NOT EXISTS idx_user_cat ON user_memory(user_id, category);
CREATE INDEX IF NOT EXISTS idx_agent_turn_conv ON agent_turn(conversation_id, turn_index);

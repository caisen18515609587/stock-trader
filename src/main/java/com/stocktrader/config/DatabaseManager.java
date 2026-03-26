package com.stocktrader.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库管理器（SQLite）
 * <p>
 * 职责：
 * 1. 管理 SQLite 数据库连接
 * 2. 初始化所有表结构
 * 3. 提供全局单例连接
 * <p>
 * 表结构：
 * - t_user          用户信息
 * - t_portfolio     账户/投资组合
 * - t_position      持仓
 * - t_order_history 交易历史
 * <p>
 * [P0-1 优化] 连接安全策略：
 * - save() 已使用 newConnection() 短连接（每次独立事务，用完关闭）
 * - load()/exists() 等读操作同样使用 newConnection() 短连接，彻底杜绝并发连接冲突
 * - 旧的 getConnection() 保留仅用于初始化阶段（建表/迁移），日常业务不再使用全局共享连接
 */
public class DatabaseManager {

    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private static volatile DatabaseManager instance;
    private final String dbPath;
    /** 仅用于初始化阶段（建表/迁移），业务代码请使用 newConnection() */
    private Connection connection;

    private DatabaseManager(String dataDir) {
        this.dbPath = dataDir + "/stock_trader.db";
    }

    /** 获取单例（线程安全） */
    public static synchronized DatabaseManager getInstance(String dataDir) {
        if (instance == null) {
            instance = new DatabaseManager(dataDir);
            instance.init();
        }
        return instance;
    }

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("DatabaseManager 尚未初始化，请先调用 getInstance(dataDir)");
        }
        return instance;
    }

    /** 初始化：建立连接并创建表 */
    private void init() {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            // 开启 WAL 模式，提升并发读写性能
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
            }
            createTables();
            migrate();
            log.info("数据库初始化成功: {}", dbPath);
        } catch (Exception e) {
            log.error("数据库初始化失败", e);
            throw new RuntimeException("数据库初始化失败", e);
        }
    }

    /**
     * 数据库迁移：幂等执行，确保新版本的索引/结构在旧库上也能生效。
     * 每次启动时自动检测并补充缺失的唯一索引，不影响已有数据。
     *
     * <p>唯一索引策略说明：
     * 旧版唯一键为 (account_id, stock_code, sell_time, quantity)，
     * 但同一时刻同一只股票可能发生多次不同价格的减仓（如尾盘分批止盈），
     * 若 sell_time 精度不足（秒级相同）或 quantity 相同，会误判为重复记录。
     * 新版唯一键在此基础上增加 sell_price 和 avg_cost，大幅降低误判概率。
     *
     * <p>去重安全原则：
     * 仅删除在新唯一键 (account_id, stock_code, sell_time, quantity, sell_price, avg_cost)
     * 下完全相同的行（即真正的重复行），保留 id 最大的那条，绝不删除合法的减仓记录。
     */
    private void migrate() {
        // ---- 步骤1：删除旧的、粒度过粗的唯一索引（若存在）----
        try (Statement st = connection.createStatement()) {
            st.execute("DROP INDEX IF EXISTS idx_closed_pos_unique");
            log.debug("已移除旧版粗粒度唯一索引 idx_closed_pos_unique（若存在）");
        } catch (SQLException e) {
            log.warn("移除旧版唯一索引时异常（可忽略）: {}", e.getMessage());
        }

        // ---- 步骤2：精准去重——仅删除在新唯一键下完全相同的重复行 ----
        // 新唯一键：account_id + stock_code + sell_time + quantity + sell_price + avg_cost
        // 这6个字段完全相同才算真正的重复记录（旧版 save() 误用 now() 时产生的冗余数据）
        try (Statement st = connection.createStatement()) {
            // 先统计有多少真正的重复行（使用 try-with-resources 确保 ResultSet 不泄漏）
            int dupCount = 0;
            try (java.sql.ResultSet rs = st.executeQuery(
                "SELECT COUNT(*) FROM t_closed_position WHERE id NOT IN (" +
                "  SELECT MAX(id) FROM t_closed_position " +
                "  GROUP BY account_id, stock_code, sell_time, quantity, sell_price, avg_cost)")) {
                dupCount = rs.next() ? rs.getInt(1) : 0;
            }

            if (dupCount > 0) {
                log.warn("检测到 {} 条完全重复的平仓记录，将精准删除（保留每组最新一条）", dupCount);
                st.execute(
                    "DELETE FROM t_closed_position WHERE id NOT IN (" +
                    "  SELECT MAX(id) FROM t_closed_position " +
                    "  GROUP BY account_id, stock_code, sell_time, quantity, sell_price, avg_cost)");
                log.info("精准去重完成，已删除 {} 条真正的重复平仓记录", dupCount);
            } else {
                log.debug("t_closed_position 无重复数据，跳过去重");
            }
        } catch (SQLException e) {
            log.warn("精准去重时异常（可忽略，不影响主流程）: {}", e.getMessage());
        }

        // ---- 步骤3：建立新的、粒度更细的唯一索引 ----
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "idx_closed_pos_unique ON t_closed_position" +
                    "(account_id, stock_code, sell_time, quantity, sell_price, avg_cost)");
            log.debug("数据库迁移完成：新版唯一索引已就绪");
        } catch (SQLException e) {
            log.error("建立新版唯一索引失败（仍存在重复数据？）: {}", e.getMessage());
        }

        // ---- 步骤4：t_position 表补充 atr_stop_price 列（幂等，列已存在则跳过）----
        // 该列存储买入时策略层计算的 ATR 动态止损价，供分钟级快速止损路径读取，
        // 保证两条止损路径（全量扫描策略层 + 分钟级快速检查）使用相同的止损标准。
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE t_position ADD COLUMN atr_stop_price REAL NOT NULL DEFAULT 0");
            log.info("数据库迁移：t_position 新增 atr_stop_price 列（ATR动态止损价）");
        } catch (SQLException e) {
            // SQLite 不支持 IF NOT EXISTS for ALTER TABLE，列已存在时会抛异常，直接忽略
            log.debug("t_position.atr_stop_price 列已存在，跳过（{}）", e.getMessage());
        }

        // ---- 步骤5：t_portfolio 表补充 large_loss_cooldown_date 列（幂等，列已存在则跳过）----
        // [P3-1 优化] 该列持久化"大亏后次日冷却期"触发日期，防止服务重启后冷却期状态丢失。
        // 当日亏损>3%时写入当天日期，次日程序启动时读取此值并恢复冷却期约束。
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE t_portfolio ADD COLUMN large_loss_cooldown_date TEXT");
            log.info("数据库迁移：t_portfolio 新增 large_loss_cooldown_date 列（大亏冷却期触发日期）");
        } catch (SQLException e) {
            // SQLite 不支持 IF NOT EXISTS for ALTER TABLE，列已存在时会抛异常，直接忽略
            log.debug("t_portfolio.large_loss_cooldown_date 列已存在，跳过（{}）", e.getMessage());
        }
    }

    /**
     * 获取共享连接（仅供初始化阶段内部使用，业务代码请改用 newConnection()）
     * @deprecated 请使用 {@link #newConnection()} 替代，避免并发下连接状态冲突
     */
    @Deprecated
    public synchronized Connection getConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                try (Statement st = connection.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA foreign_keys=ON");
                }
                log.info("数据库重新连接成功");
            }
        } catch (SQLException e) {
            log.error("获取数据库连接失败", e);
            throw new RuntimeException(e);
        }
        return connection;
    }

    /**
     * 创建一个新的独立短连接（推荐所有业务操作使用此方法）
     * <p>
     * [P0-1 优化] 每次请求独立短连接，用完必须在 finally 中关闭。
     * WAL 模式支持多读写连接并发，SQLite 内部串行化写事务，读写不互相阻塞。
     * 相比共享连接方案，彻底解决多用户线程并发时的连接状态冲突问题。
     */
    public Connection newConnection() {
        try {
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=5000");  // 等待最多5秒，避免写冲突立即报错
            }
            return conn;
        } catch (SQLException e) {
            log.error("创建新数据库连接失败", e);
            throw new RuntimeException(e);
        }
    }

    /** 创建所有表（若不存在） */
    private void createTables() throws SQLException {
        String[] ddls = {
            // ===== 用户表 =====
            "CREATE TABLE IF NOT EXISTS t_user (" +
            "  user_id          TEXT PRIMARY KEY," +
            "  username         TEXT NOT NULL UNIQUE," +
            "  password_hash    TEXT NOT NULL," +
            "  nickname         TEXT," +
            "  email            TEXT," +
            "  initial_capital  REAL NOT NULL DEFAULT 100000," +
            "  strategy_type    TEXT NOT NULL DEFAULT 'DAY_TRADE'," +
            "  strategy_config  TEXT," +
            "  wechat_send_key  TEXT," +
            "  status           TEXT NOT NULL DEFAULT 'ACTIVE'," +
            "  is_super_admin   INTEGER NOT NULL DEFAULT 0," +
            "  create_time      TEXT," +
            "  last_login_time  TEXT" +
            ")",

            // ===== 账户/投资组合表 =====
            // 注意：user_id 不做外键约束，兼容旧的全局账户（auto_trader_main）
            "CREATE TABLE IF NOT EXISTS t_portfolio (" +
            "  account_id       TEXT PRIMARY KEY," +
            "  user_id          TEXT," +
            "  account_name     TEXT," +
            "  initial_capital  REAL NOT NULL DEFAULT 100000," +
            "  available_cash   REAL NOT NULL DEFAULT 100000," +
            "  frozen_cash      REAL NOT NULL DEFAULT 0," +
            "  mode             TEXT NOT NULL DEFAULT 'SIMULATION'," +
            "  create_time      TEXT," +
            "  update_time      TEXT" +
            ")",

            // ===== 持仓表 =====
            "CREATE TABLE IF NOT EXISTS t_position (" +
            "  id               INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  account_id       TEXT NOT NULL," +
            "  stock_code       TEXT NOT NULL," +
            "  stock_name       TEXT," +
            "  quantity         INTEGER NOT NULL DEFAULT 0," +
            "  available_qty    INTEGER NOT NULL DEFAULT 0," +
            "  avg_cost         REAL NOT NULL DEFAULT 0," +
            "  current_price    REAL NOT NULL DEFAULT 0," +
            "  market_value     REAL NOT NULL DEFAULT 0," +
            "  last_buy_date    TEXT," +
            "  first_buy_time   TEXT," +
            "  atr_stop_price   REAL NOT NULL DEFAULT 0," +
            "  update_time      TEXT," +
            "  UNIQUE(account_id, stock_code)," +
            "  FOREIGN KEY(account_id) REFERENCES t_portfolio(account_id)" +
            ")",

            // ===== 交易历史表 =====
            "CREATE TABLE IF NOT EXISTS t_order_history (" +
            "  id               INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  order_id         TEXT NOT NULL UNIQUE," +
            "  account_id       TEXT NOT NULL," +
            "  stock_code       TEXT NOT NULL," +
            "  stock_name       TEXT," +
            "  order_type       TEXT NOT NULL," +
            "  status           TEXT NOT NULL," +
            "  price            REAL NOT NULL DEFAULT 0," +
            "  quantity         INTEGER NOT NULL DEFAULT 0," +
            "  filled_price     REAL NOT NULL DEFAULT 0," +
            "  filled_quantity  INTEGER NOT NULL DEFAULT 0," +
            "  amount           REAL NOT NULL DEFAULT 0," +
            "  commission       REAL NOT NULL DEFAULT 0," +
            "  stamp_tax        REAL NOT NULL DEFAULT 0," +
            "  transfer_fee     REAL NOT NULL DEFAULT 0," +
            "  total_fee        REAL NOT NULL DEFAULT 0," +
            "  strategy_name    TEXT," +
            "  remark           TEXT," +
            "  filled_time      TEXT," +
            "  create_time      TEXT," +
            "  FOREIGN KEY(account_id) REFERENCES t_portfolio(account_id)" +
            ")",

            // ===== 已平仓记录表 =====
            "CREATE TABLE IF NOT EXISTS t_closed_position (" +
            "  id               INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  account_id       TEXT NOT NULL," +
            "  stock_code       TEXT NOT NULL," +
            "  stock_name       TEXT," +
            "  quantity         INTEGER NOT NULL DEFAULT 0," +
            "  avg_cost         REAL NOT NULL DEFAULT 0," +
            "  sell_price       REAL NOT NULL DEFAULT 0," +
            "  total_fee        REAL NOT NULL DEFAULT 0," +
            "  realized_pnl     REAL NOT NULL DEFAULT 0," +
            "  realized_pnl_rate REAL NOT NULL DEFAULT 0," +
            "  close_reason     TEXT," +
            "  buy_time         TEXT," +
            "  sell_time        TEXT NOT NULL," +
            "  FOREIGN KEY(account_id) REFERENCES t_portfolio(account_id)" +
            ")",

            // ===== 实盘操作撤销记录表 =====
            // 记录每次因"用户反馈实盘失败"而触发的模拟账户回滚操作，可用于统计分析
            "CREATE TABLE IF NOT EXISTS t_rollback_record (" +
            "  id               INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  account_id       TEXT NOT NULL," +
            "  rollback_type    TEXT NOT NULL," +   // BUY_FAIL / SELL_FAIL
            "  stock_code       TEXT NOT NULL," +
            "  stock_name       TEXT," +
            "  sim_price        REAL NOT NULL DEFAULT 0," +  // 模拟成交价格
            "  sim_quantity     INTEGER NOT NULL DEFAULT 0," + // 模拟成交数量
            "  sim_amount       REAL NOT NULL DEFAULT 0," +   // 模拟成交金额（含手续费）
            "  refund_cash      REAL NOT NULL DEFAULT 0," +   // 撤销后退回/补还的资金
            "  rollback_reason  TEXT," +                      // 撤销原因描述
            "  rollback_time    TEXT NOT NULL" +               // 撤销时间
            ")",

            // ===== 索引 =====
            "CREATE INDEX IF NOT EXISTS idx_order_account ON t_order_history(account_id)",
            "CREATE INDEX IF NOT EXISTS idx_order_stock   ON t_order_history(stock_code)",
            "CREATE INDEX IF NOT EXISTS idx_order_time    ON t_order_history(filled_time)",
            "CREATE INDEX IF NOT EXISTS idx_position_acct ON t_position(account_id)",
            "CREATE INDEX IF NOT EXISTS idx_portfolio_user ON t_portfolio(user_id)",
            "CREATE INDEX IF NOT EXISTS idx_closed_pos_acct ON t_closed_position(account_id)",
            "CREATE INDEX IF NOT EXISTS idx_closed_pos_time ON t_closed_position(sell_time)",
            "CREATE INDEX IF NOT EXISTS idx_rollback_acct  ON t_rollback_record(account_id)",
            "CREATE INDEX IF NOT EXISTS idx_rollback_time  ON t_rollback_record(rollback_time)"
        };

        try (Statement st = connection.createStatement()) {
            for (String ddl : ddls) {
                st.execute(ddl);
            }
        }
        log.info("数据库表结构已就绪");
    }

    /** 关闭连接 */
    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                log.info("数据库连接已关闭");
            }
        } catch (SQLException e) {
            log.error("关闭数据库连接失败", e);
        }
    }

    public String getDbPath() { return dbPath; }
}


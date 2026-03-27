package com.stocktrader.util;

import com.stocktrader.config.DatabaseManager;
import com.stocktrader.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户数据持久化存储（SQLite 版）
 * <p>
 * 存储结构：
 *   t_user 表    ← 所有用户信息
 *   data/users/{userId}/   ← 每个用户的交易报告目录（仍保留）
 */
public class UserStore {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String dataDir;
    private final DatabaseManager db;

    // 内存缓存（userId -> User），避免频繁查库
    private final Map<String, User> userCache = new ConcurrentHashMap<>();
    // 用户名索引（username小写 -> userId）
    private final Map<String, String> usernameIndex = new ConcurrentHashMap<>();

    public UserStore(String dataDir) {
        this.dataDir = dataDir;
        this.db = DatabaseManager.getInstance(dataDir);
        new File(dataDir + "/users").mkdirs();
        loadAll();
    }

    // =================== 增删改查 ===================

    /** 保存或更新用户（数据库 + 内存缓存同步） */
    public synchronized void save(User user) {
        upsertToDb(user);
        userCache.put(user.getUserId(), user);
        usernameIndex.put(user.getUsername().toLowerCase(), user.getUserId());
        // 确保用户报告目录存在
        ensureUserDirs(user.getUserId());
        log.debug("用户已保存(DB): {}", user.getUsername());
    }

    /** 根据 userId 查找用户 */
    public User findById(String userId) {
        return userCache.get(userId);
    }

    /** 根据用户名查找用户 */
    public User findByUsername(String username) {
        String userId = usernameIndex.get(username.toLowerCase());
        return userId != null ? userCache.get(userId) : null;
    }

    /** 判断用户名是否已存在 */
    public boolean usernameExists(String username) {
        return usernameIndex.containsKey(username.toLowerCase());
    }

    /** 获取所有用户列表（副本） */
    public List<User> findAll() {
        return new ArrayList<>(userCache.values());
    }

    /** 获取该用户的数据目录路径 */
    public String getUserDir(String userId) {
        return dataDir + "/users/" + userId;
    }

    /** 获取该用户的账户持久化目录（AccountPersistence 使用，返回 dataDir 标记，数据库模式下仅作占位） */
    public String getUserAccountDir(String userId) {
        return getUserDir(userId);
    }

    /** 获取该用户的交易报告目录 */
    public String getUserReportDir(String userId) {
        return getUserDir(userId) + "/trade-reports";
    }

    // =================== 数据库操作 ===================

    /** INSERT OR REPLACE 到 t_user 表（含 wechat_open_id、market 字段） */
    private void upsertToDb(User user) {
        String sql = "INSERT OR REPLACE INTO t_user(" +
                "user_id, username, password_hash, nickname, email, initial_capital, " +
                "strategy_type, strategy_config, wechat_open_id, wechat_send_key, status, is_super_admin, " +
                "create_time, last_login_time, market) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        // [P0-1 优化] 使用独立短连接避免并发冲突
        Connection conn = null;
        try {
            conn = db.newConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1,  user.getUserId());
                ps.setString(2,  user.getUsername());
                ps.setString(3,  user.getPasswordHash());
                ps.setString(4,  user.getNickname() != null ? user.getNickname() : "");
                ps.setString(5,  user.getEmail() != null ? user.getEmail() : "");
                ps.setDouble(6,  user.getInitialCapital());
                ps.setString(7,  user.getStrategyType() != null ? user.getStrategyType().name() : "DAY_TRADE");
                ps.setString(8,  user.getStrategyConfigJson() != null ? user.getStrategyConfigJson() : "");
                ps.setString(9,  user.getWechatOpenId() != null ? user.getWechatOpenId() : "");
                ps.setString(10, user.getWechatSendKey() != null ? user.getWechatSendKey() : "");
                ps.setString(11, user.getStatus() != null ? user.getStatus().name() : "ACTIVE");
                ps.setInt(12,    user.isSuperAdmin() ? 1 : 0);
                ps.setString(13, user.getCreateTime() != null ? user.getCreateTime().format(DT_FMT) : "");
                ps.setString(14, user.getLastLoginTime() != null ? user.getLastLoginTime().format(DT_FMT) : "");
                ps.setString(15, user.getMarket() != null ? user.getMarket() : "CN");
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.error("保存用户到数据库失败: {}", user.getUsername(), e);
        } finally {
            if (conn != null) { try { conn.close(); } catch (Exception ignore) {} }
        }
    }

    /** 检测并添加 wechat_open_id 列（数据库升级兼容），仅在初始化时调用一次 */
    private void ensureWechatOpenIdColumn() {
        // 使用独立短连接，避免共享连接并发问题，Statement 放入 try-with-resources 防止泄漏
        Connection conn = null;
        try {
            conn = db.newConnection();
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE t_user ADD COLUMN wechat_open_id TEXT DEFAULT ''");
                log.info("[DB迁移] t_user 表已添加 wechat_open_id 列");
            }
        } catch (SQLException e) {
            // 列已存在时 SQLite 会抛出异常，这是正常的，忽略即可
        } finally {
            if (conn != null) { try { conn.close(); } catch (Exception ignore) {} }
        }
    }

    /** 检测并添加 market 列（数据库升级兼容），仅在初始化时调用一次 */
    private void ensureMarketColumn() {
        Connection conn = null;
        try {
            conn = db.newConnection();
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE t_user ADD COLUMN market TEXT NOT NULL DEFAULT 'CN'");
                log.info("[DB迁移] t_user 表已添加 market 列");
            }
        } catch (SQLException e) {
            // 列已存在时 SQLite 会抛出异常，忽略
        } finally {
            if (conn != null) { try { conn.close(); } catch (Exception ignore) {} }
        }
    }

    /** 启动时从数据库加载所有用户到内存缓存 */
    private void loadAll() {
        // 启动时确保新列存在（防止首次启动时 SELECT 失败）
        ensureWechatOpenIdColumn();
        ensureMarketColumn();
        String sql = "SELECT user_id, username, password_hash, nickname, email, initial_capital, " +
                "strategy_type, strategy_config, wechat_open_id, wechat_send_key, status, is_super_admin, " +
                "create_time, last_login_time, market FROM t_user";
        // 使用独立短连接，避免与业务代码共享连接导致并发冲突
        Connection conn = null;
        try {
            conn = db.newConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    User u = mapRow(rs);
                    if (u.getUserId() != null && !u.getUserId().isEmpty()) {
                        userCache.put(u.getUserId(), u);
                        usernameIndex.put(u.getUsername().toLowerCase(), u.getUserId());
                        ensureUserDirs(u.getUserId());
                        count++;
                    }
                }
                log.info("从数据库加载 {} 个用户", count);
            }
        } catch (SQLException e) {
            log.error("从数据库加载用户失败", e);
        } finally {
            if (conn != null) { try { conn.close(); } catch (Exception ignore) {} }
        }
    }

    /** ResultSet 映射为 User 对象 */
    private User mapRow(ResultSet rs) throws SQLException {
        User u = new User();
        u.setUserId(rs.getString("user_id"));
        u.setUsername(rs.getString("username"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setNickname(rs.getString("nickname"));
        u.setEmail(rs.getString("email"));
        u.setInitialCapital(rs.getDouble("initial_capital"));
        try {
            u.setStrategyType(User.StrategyType.valueOf(rs.getString("strategy_type")));
        } catch (Exception e) {
            u.setStrategyType(User.StrategyType.DAY_TRADE);
        }
        u.setStrategyConfigJson(rs.getString("strategy_config"));
        u.setWechatOpenId(rs.getString("wechat_open_id"));
        u.setWechatSendKey(rs.getString("wechat_send_key"));
        try {
            u.setStatus(User.UserStatus.valueOf(rs.getString("status")));
        } catch (Exception e) {
            u.setStatus(User.UserStatus.ACTIVE);
        }
        u.setSuperAdmin(rs.getInt("is_super_admin") == 1);
        String ct = rs.getString("create_time");
        if (ct != null && !ct.isEmpty()) {
            try { u.setCreateTime(LocalDateTime.parse(ct, DT_FMT)); } catch (Exception ignored) {}
        }
        String lt = rs.getString("last_login_time");
        if (lt != null && !lt.isEmpty()) {
            try { u.setLastLoginTime(LocalDateTime.parse(lt, DT_FMT)); } catch (Exception ignored) {}
        }
        // market 字段（HK=港股, CN=A股，默认CN）
        try {
            String market = rs.getString("market");
            u.setMarket(market != null && !market.isEmpty() ? market : "CN");
        } catch (Exception e) {
            u.setMarket("CN");
        }
        return u;
    }

    private void ensureUserDirs(String userId) {
        new File(getUserDir(userId)).mkdirs();
        new File(getUserDir(userId) + "/trade-reports").mkdirs();
        new File(getUserDir(userId) + "/trade-reports/daily").mkdirs();
        new File(getUserDir(userId) + "/trade-reports/trades").mkdirs();
    }
}


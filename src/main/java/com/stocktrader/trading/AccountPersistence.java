package com.stocktrader.trading;

import com.stocktrader.config.DatabaseManager;
import com.stocktrader.model.ClosedPosition;
import com.stocktrader.model.Order;
import com.stocktrader.model.Portfolio;
import com.stocktrader.model.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 账户持久化管理器（SQLite 版）
 * <p>
 * 将账户状态（资金、持仓、交易历史）读写到 SQLite 数据库，
 * 程序重启后可恢复上次状态，避免模拟交易数据丢失。
 * <p>
 * 表结构：
 *   t_portfolio     账户基础信息（资金）
 *   t_position      当前持仓
 *   t_order_history 交易历史
 */
public class AccountPersistence {

    private static final Logger log = LoggerFactory.getLogger(AccountPersistence.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DatabaseManager db;

    /**
     * @param dataDir 数据目录（与 DatabaseManager 初始化目录一致）
     *                注意：多用户模式下传入的可能是 data/users/{userId} 这样的用户子目录，
     *                但 DatabaseManager 单例在 UserStore 初始化时已经以根 data 目录创建，
     *                因此这里直接获取单例即可，不依赖传入的路径。
     */
    public AccountPersistence(String dataDir) {
        // DatabaseManager 单例由 UserStore 启动时统一初始化（根 data 目录）
        // 此处忽略 dataDir 参数，直接获取全局单例
        DatabaseManager mgr;
        try {
            mgr = DatabaseManager.getInstance();
        } catch (IllegalStateException e) {
            // 若直接使用 AutoTrader 单机模式，UserStore 未初始化，则用传入目录初始化
            mgr = DatabaseManager.getInstance(dataDir);
        }
        this.db = mgr;
    }

    // ========== 保存 ==========

    /**
     * 保存账户状态到数据库（全量覆盖：账户信息 + 持仓 + 最新500条订单）
     */
    public void save(Portfolio portfolio) {
        // 每次 save() 使用独立连接，避免多账号线程共享同一连接导致事务状态冲突
        Connection conn = null;
        try {
            conn = db.newConnection();
            conn.setAutoCommit(false);
            try {
                savePortfolio(conn, portfolio);
                savePositions(conn, portfolio);
                saveOrders(conn, portfolio);
                saveClosedPositions(conn, portfolio);
                conn.commit();
                log.debug("账户已保存(DB): {} 持仓{}只 订单{}条",
                        portfolio.getAccountId(),
                        portfolio.getPositions().size(),
                        portfolio.getOrderHistory().size());
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            log.error("保存账户失败: {}", portfolio.getAccountId(), e);
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception ignore) {}
            }
        }
    }

    private void savePortfolio(Connection conn, Portfolio portfolio) throws SQLException {
        String sql = "INSERT OR REPLACE INTO t_portfolio(" +
                "account_id, user_id, account_name, initial_capital, available_cash, frozen_cash, " +
                "mode, create_time, update_time) VALUES(?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, portfolio.getAccountId());
            // user_id：若 accountId 形如 "userId_auto"，取前半部分；否则直接用 accountId
            ps.setString(2, extractUserId(portfolio.getAccountId()));
            ps.setString(3, portfolio.getAccountName());
            ps.setDouble(4, portfolio.getInitialCapital());
            ps.setDouble(5, portfolio.getAvailableCash());
            ps.setDouble(6, portfolio.getFrozenCash());
            ps.setString(7, portfolio.getMode() != null ? portfolio.getMode().name() : "SIMULATION");
            ps.setString(8, portfolio.getCreateTime() != null ? portfolio.getCreateTime().format(DT_FMT) : now());
            ps.setString(9, now());
            ps.executeUpdate();
        }
    }

    /** 全量替换持仓：先删后插 */
    private void savePositions(Connection conn, Portfolio portfolio) throws SQLException {
        // 删除该账户旧持仓
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM t_position WHERE account_id = ?")) {
            ps.setString(1, portfolio.getAccountId());
            ps.executeUpdate();
        }
        // 插入新持仓
        String sql = "INSERT INTO t_position(account_id, stock_code, stock_name, quantity, " +
                "available_qty, avg_cost, current_price, market_value, " +
                "last_buy_date, first_buy_time, atr_stop_price, update_time) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Position p : portfolio.getPositions().values()) {
                ps.setString(1, portfolio.getAccountId());
                ps.setString(2, p.getStockCode());
                ps.setString(3, p.getStockName() != null ? p.getStockName() : "");
                ps.setInt(4,    p.getQuantity());
                ps.setInt(5,    p.getAvailableQuantity());
                ps.setDouble(6, p.getAvgCost());
                ps.setDouble(7, p.getCurrentPrice());
                ps.setDouble(8, p.getMarketValue());
                ps.setString(9, p.getLastBuyDate() != null ? p.getLastBuyDate().toString() : null);
                ps.setString(10, p.getFirstBuyTime() != null ? p.getFirstBuyTime().format(DT_FMT) : null);
                ps.setDouble(11, p.getAtrStopPrice());
                ps.setString(12, now());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 增量保存订单：只插入数据库中不存在的订单（按 order_id 去重） */
    private void saveOrders(Connection conn, Portfolio portfolio) throws SQLException {
        String sql = "INSERT OR IGNORE INTO t_order_history(" +
                "order_id, account_id, stock_code, stock_name, order_type, status, " +
                "price, quantity, filled_price, filled_quantity, amount, commission, " +
                "stamp_tax, transfer_fee, total_fee, strategy_name, remark, filled_time, create_time) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        // 只保留最近500条
        java.util.List<Order> orders = portfolio.getOrderHistory();
        int skip = Math.max(0, orders.size() - 500);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = skip; i < orders.size(); i++) {
                Order o = orders.get(i);
                ps.setString(1,  o.getOrderId());
                ps.setString(2,  portfolio.getAccountId());
                ps.setString(3,  o.getStockCode());
                ps.setString(4,  o.getStockName() != null ? o.getStockName() : "");
                ps.setString(5,  o.getOrderType() != null ? o.getOrderType().name() : "BUY");
                ps.setString(6,  o.getStatus() != null ? o.getStatus().name() : "FILLED");
                ps.setDouble(7,  o.getPrice());
                ps.setInt(8,     o.getQuantity());
                ps.setDouble(9,  o.getFilledPrice());
                ps.setInt(10,    o.getFilledQuantity());
                ps.setDouble(11, o.getAmount());
                ps.setDouble(12, o.getCommission());
                ps.setDouble(13, o.getStampTax());
                ps.setDouble(14, o.getTransferFee());
                ps.setDouble(15, o.getTotalFee());
                ps.setString(16, o.getStrategyName() != null ? o.getStrategyName() : "");
                ps.setString(17, o.getRemark() != null ? o.getRemark() : "");
                ps.setString(18, o.getFilledTime() != null ? o.getFilledTime().format(DT_FMT) : null);
                ps.setString(19, o.getCreateTime() != null ? o.getCreateTime().format(DT_FMT) : now());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * 增量写入已平仓记录：仅插入数据库中不存在的新记录，永不删除历史数据。
     * <p>
     * 使用 INSERT OR IGNORE 配合唯一索引 (account_id, stock_code, sell_time, quantity)
     * 保证幂等性：同一笔平仓记录无论保存多少次都不会重复，历史数据也不会因重启而丢失。
     */
    private void saveClosedPositions(Connection conn, Portfolio portfolio) throws SQLException {
        java.util.List<ClosedPosition> list = portfolio.getClosedPositions();
        if (list == null || list.isEmpty()) return;

        // INSERT OR IGNORE：依赖唯一索引 idx_closed_pos_unique 去重，已存在的记录自动跳过
        String sql = "INSERT OR IGNORE INTO t_closed_position(" +
                "account_id, stock_code, stock_name, quantity, avg_cost, sell_price, " +
                "total_fee, realized_pnl, realized_pnl_rate, close_reason, buy_time, sell_time) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ClosedPosition cp : list) {
                ps.setString(1,  portfolio.getAccountId());
                ps.setString(2,  cp.getStockCode());
                ps.setString(3,  cp.getStockName() != null ? cp.getStockName() : "");
                ps.setInt(4,     cp.getQuantity());
                ps.setDouble(5,  cp.getAvgCost());
                ps.setDouble(6,  cp.getSellPrice());
                ps.setDouble(7,  cp.getTotalFee());
                ps.setDouble(8,  cp.getRealizedPnl());
                ps.setDouble(9,  cp.getRealizedPnlRate());
                ps.setString(10, cp.getCloseReason() != null ? cp.getCloseReason() : "");
                ps.setString(11, cp.getBuyTime() != null ? cp.getBuyTime().format(DT_FMT) : null);
                ps.setString(12, cp.getSellTime() != null ? cp.getSellTime().format(DT_FMT) : now());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ========== 加载 ==========

    /**
     * 从数据库恢复账户状态
     * <p>
     * [P0-1 优化] 使用独立短连接，避免并发读写时共享连接状态冲突
     */
    public Portfolio load(String accountId) {
        Connection conn = null;
        try {
            conn = db.newConnection();
            Portfolio portfolio = loadPortfolio(conn, accountId);
            if (portfolio == null) return null;
            loadPositions(conn, portfolio);
            loadOrders(conn, portfolio);
            loadClosedPositions(conn, portfolio);
            log.info("账户已恢复(DB): {} 资金={} 持仓={}只 历史订单={}条",
                    portfolio.getAccountName(),
                    String.format("%.2f", portfolio.getAvailableCash()),
                    portfolio.getPositions().size(),
                    portfolio.getOrderHistory().size());
            return portfolio;
        } catch (Exception e) {
            log.error("恢复账户失败: {}", accountId, e);
            return null;
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception ignore) {}
            }
        }
    }

    private Portfolio loadPortfolio(Connection conn, String accountId) throws SQLException {
        String sql = "SELECT account_id, account_name, initial_capital, available_cash, " +
                "frozen_cash, mode FROM t_portfolio WHERE account_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String modeStr = rs.getString("mode");
                Portfolio.AccountMode mode;
                try {
                    mode = Portfolio.AccountMode.valueOf(modeStr);
                } catch (Exception e) {
                    mode = Portfolio.AccountMode.SIMULATION;
                }
                Portfolio p = new Portfolio(
                        rs.getString("account_id"),
                        rs.getString("account_name"),
                        rs.getDouble("initial_capital"),
                        mode
                );
                p.setAvailableCash(rs.getDouble("available_cash"));
                p.setFrozenCash(rs.getDouble("frozen_cash"));
                return p;
            }
        }
    }

    private void loadPositions(Connection conn, Portfolio portfolio) throws SQLException {
        String sql = "SELECT stock_code, stock_name, quantity, available_qty, avg_cost, " +
                "current_price, market_value, last_buy_date, first_buy_time, atr_stop_price " +
                "FROM t_position WHERE account_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, portfolio.getAccountId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LocalDate lastBuyDate = null;
                    String lbd = rs.getString("last_buy_date");
                    if (lbd != null && !lbd.isEmpty()) {
                        try { lastBuyDate = LocalDate.parse(lbd); } catch (Exception ignored) {}
                    }
                    int quantity     = rs.getInt("quantity");
                    int availableQty = rs.getInt("available_qty");
                    // T+1 规则：若最后买入日是今天，今日不可卖出
                    if (lastBuyDate != null && lastBuyDate.equals(LocalDate.now())) {
                        availableQty = 0;
                    }
                    LocalDateTime firstBuyTime = LocalDateTime.now();
                    String fbt = rs.getString("first_buy_time");
                    if (fbt != null && !fbt.isEmpty()) {
                        try { firstBuyTime = LocalDateTime.parse(fbt, DT_FMT); } catch (Exception ignored) {}
                    }
                    // 读取 ATR 动态止损价（旧持仓无该字段时默认0，表示降级为固定止损）
                    double atrStopPrice = 0;
                    try { atrStopPrice = rs.getDouble("atr_stop_price"); } catch (Exception ignored) {}
                    Position pos = Position.builder()
                            .stockCode(rs.getString("stock_code"))
                            .stockName(rs.getString("stock_name"))
                            .quantity(quantity)
                            .availableQuantity(availableQty)
                            .avgCost(rs.getDouble("avg_cost"))
                            .currentPrice(rs.getDouble("current_price"))
                            .marketValue(rs.getDouble("market_value"))
                            .lastBuyDate(lastBuyDate)
                            .firstBuyTime(firstBuyTime)
                            .atrStopPrice(atrStopPrice)
                            .build();
                    portfolio.getPositions().put(pos.getStockCode(), pos);
                }
            }
        }
    }

    private void loadOrders(Connection conn, Portfolio portfolio) throws SQLException {
        // 加载最近500条订单，按时间降序取
        String sql = "SELECT order_id, stock_code, stock_name, order_type, status, price, quantity, " +
                "filled_price, filled_quantity, amount, commission, stamp_tax, transfer_fee, " +
                "total_fee, strategy_name, remark, filled_time, create_time " +
                "FROM t_order_history WHERE account_id = ? ORDER BY id DESC LIMIT 500";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, portfolio.getAccountId());
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<Order> orders = new java.util.ArrayList<>();
                while (rs.next()) {
                    try {
                        Order.OrderType ot = Order.OrderType.valueOf(rs.getString("order_type"));
                        Order.OrderStatus os = Order.OrderStatus.valueOf(rs.getString("status"));
                        LocalDateTime ft = null;
                        String ftStr = rs.getString("filled_time");
                        if (ftStr != null && !ftStr.isEmpty()) {
                            try { ft = LocalDateTime.parse(ftStr, DT_FMT); } catch (Exception ignored) {}
                        }
                        LocalDateTime ct = null;
                        String ctStr = rs.getString("create_time");
                        if (ctStr != null && !ctStr.isEmpty()) {
                            try { ct = LocalDateTime.parse(ctStr, DT_FMT); } catch (Exception ignored) {}
                        }
                        Order order = Order.builder()
                                .orderId(rs.getString("order_id"))
                                .stockCode(rs.getString("stock_code"))
                                .stockName(rs.getString("stock_name"))
                                .orderType(ot)
                                .status(os)
                                .price(rs.getDouble("price"))
                                .quantity(rs.getInt("quantity"))
                                .filledPrice(rs.getDouble("filled_price"))
                                .filledQuantity(rs.getInt("filled_quantity"))
                                .amount(rs.getDouble("amount"))
                                .commission(rs.getDouble("commission"))
                                .stampTax(rs.getDouble("stamp_tax"))
                                .transferFee(rs.getDouble("transfer_fee"))
                                .totalFee(rs.getDouble("total_fee"))
                                .strategyName(rs.getString("strategy_name"))
                                .remark(rs.getString("remark"))
                                .createTime(ct != null ? ct : ft)
                                .filledTime(ft)
                                .build();
                        orders.add(order);
                    } catch (Exception e) {
                        log.debug("恢复订单行失败: {}", e.getMessage());
                    }
                }
                // 反转为时间升序
                java.util.Collections.reverse(orders);
                portfolio.getOrderHistory().addAll(orders);
            }
        }
    }

    /**
     * 加载已平仓记录（按卖出时间升序）
     */
    private void loadClosedPositions(Connection conn, Portfolio portfolio) throws SQLException {
        String sql = "SELECT stock_code, stock_name, quantity, avg_cost, sell_price, " +
                "total_fee, realized_pnl, realized_pnl_rate, close_reason, buy_time, sell_time " +
                "FROM t_closed_position WHERE account_id = ? ORDER BY sell_time ASC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, portfolio.getAccountId());
            try (ResultSet rs = ps.executeQuery()) {
                java.util.List<ClosedPosition> list = new java.util.ArrayList<>();
                while (rs.next()) {
                    LocalDateTime buyTime = null;
                    String btStr = rs.getString("buy_time");
                    if (btStr != null && !btStr.isEmpty()) {
                        try { buyTime = LocalDateTime.parse(btStr, DT_FMT); } catch (Exception ignored) {}
                    }
                    LocalDateTime sellTime = null;
                    String stStr = rs.getString("sell_time");
                    if (stStr != null && !stStr.isEmpty()) {
                        try { sellTime = LocalDateTime.parse(stStr, DT_FMT); } catch (Exception ignored) {}
                    }
                    ClosedPosition cp = ClosedPosition.builder()
                            .stockCode(rs.getString("stock_code"))
                            .stockName(rs.getString("stock_name"))
                            .quantity(rs.getInt("quantity"))
                            .avgCost(rs.getDouble("avg_cost"))
                            .sellPrice(rs.getDouble("sell_price"))
                            .totalFee(rs.getDouble("total_fee"))
                            .realizedPnl(rs.getDouble("realized_pnl"))
                            .realizedPnlRate(rs.getDouble("realized_pnl_rate"))
                            .closeReason(rs.getString("close_reason"))
                            .buyTime(buyTime)
                            .sellTime(sellTime)
                            .build();
                    list.add(cp);
                }
                portfolio.setClosedPositions(list);
                // 同步累计已实现盈亏
                double totalPnl = list.stream().mapToDouble(ClosedPosition::getRealizedPnl).sum();
                portfolio.setRealizedPnl(totalPnl);
            }
        }
    }

    // ========== 辅助 ==========

    /**
     * 检查账户是否存在（数据库中）
     * <p>
     * [P0-1 优化] 使用独立短连接
     */
    public boolean exists(String accountId) {
        String sql = "SELECT 1 FROM t_portfolio WHERE account_id = ?";
        Connection conn = null;
        try {
            conn = db.newConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            log.error("检查账户存在性失败: {}", accountId, e);
            return false;
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception ignore) {}
            }
        }
    }

    // ========== [P3-1] 大亏冷却期持久化 ==========

    /**
     * 将大亏冷却期触发日期写入数据库（UPDATE t_portfolio）。
     * <p>
     * 仅更新 large_loss_cooldown_date 字段，不影响其他账户字段。
     * triggerDate 为 null 时清除记录（冷却期已过）。
     *
     * @param accountId   账户ID
     * @param triggerDate 触发日期（null 表示清除）
     */
    public void saveLargelossCooldownDate(String accountId, LocalDate triggerDate) {
        String sql = "UPDATE t_portfolio SET large_loss_cooldown_date = ? WHERE account_id = ?";
        Connection conn = null;
        try {
            conn = db.newConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, triggerDate != null ? triggerDate.toString() : null);
                ps.setString(2, accountId);
                ps.executeUpdate();
                log.debug("[P3-1大亏冷却] 冷却期日期已持久化: accountId={} date={}", accountId, triggerDate);
            }
        } catch (Exception e) {
            log.error("[P3-1大亏冷却] 持久化冷却期日期失败: accountId={}", accountId, e);
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception ignore) {}
            }
        }
    }

    /**
     * 从数据库读取大亏冷却期触发日期。
     * <p>
     * 程序重启时调用，恢复内存中的冷却期状态，防止状态丢失。
     *
     * @param accountId 账户ID
     * @return 触发日期；若无记录或字段为空则返回 null
     */
    public LocalDate loadLargelossCooldownDate(String accountId) {
        String sql = "SELECT large_loss_cooldown_date FROM t_portfolio WHERE account_id = ?";
        Connection conn = null;
        try {
            conn = db.newConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String dateStr = rs.getString("large_loss_cooldown_date");
                        if (dateStr != null && !dateStr.isEmpty()) {
                            LocalDate date = LocalDate.parse(dateStr);
                            log.debug("[P3-1大亏冷却] 恢复冷却期日期: accountId={} date={}", accountId, date);
                            return date;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[P3-1大亏冷却] 读取冷却期日期失败（可能是旧库无此字段）: accountId={} err={}", accountId, e.getMessage());
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception ignore) {}
            }
        }
        return null;
    }

    private String now() {
        return LocalDateTime.now().format(DT_FMT);
    }

    /**
     * 从 accountId 推断 userId：
     * - "auto_trader_main" 是旧格式，对应 dataDir 下的默认账户
     * - 未来格式建议为 "{userId}_auto"
     */
    private String extractUserId(String accountId) {
        if (accountId == null) return "unknown";
        if (accountId.contains("_auto")) {
            return accountId.replace("_auto", "");
        }
        return accountId;
    }
}


package com.stocktrader.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktrader.model.Order;
import com.stocktrader.model.Portfolio;
import com.stocktrader.model.Position;
import com.stocktrader.model.User;
import com.stocktrader.trading.AccountPersistence;
import com.stocktrader.util.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 数据迁移工具
 * <p>
 * 将旧版 JSON 文件数据迁移到 SQLite 数据库。
 * <p>
 * 迁移内容：
 * 1. data/users.json                              → t_user 表
 * 2. data/auto_trader_main.json                   → t_portfolio / t_position / t_order_history（老格式）
 * 3. data/users/{userId}/auto_trader_main.json    → 每个用户的账户数据
 * <p>
 * 用法（main 方法独立运行）：
 *   java -cp stock-trader.jar com.stocktrader.config.DataMigration [dataDir]
 *   默认 dataDir = ./data
 */
public class DataMigration {

    private static final Logger log = LoggerFactory.getLogger(DataMigration.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper om = new ObjectMapper();

    /**
     * 独立运行入口
     */
    public static void main(String[] args) {
        String dataDir = args.length > 0 ? args[0] : "./data";
        new DataMigration().migrate(dataDir);
    }

    /**
     * 执行全量迁移（可被其他类调用）
     *
     * @param dataDir 数据根目录（如 ./data）
     */
    public void migrate(String dataDir) {
        log.info("========== 开始数据迁移：{} ==========", dataDir);

        // 1. 初始化数据库（建表）
        DatabaseManager db = DatabaseManager.getInstance(dataDir);
        log.info("SQLite 数据库: {}", db.getDbPath());

        // 2. 迁移用户数据
        int usersMigrated = migrateUsers(dataDir);

        // 3. 迁移账户数据（旧的 auto_trader_main.json 在根目录）
        int accountsMigrated = migrateAccounts(dataDir);

        log.info("========== 迁移完成：用户 {} 个，账户 {} 个 ==========", usersMigrated, accountsMigrated);
        System.out.printf("%n  ✅ 数据迁移完成！%n");
        System.out.printf("  📊 迁移用户：%d 个%n", usersMigrated);
        System.out.printf("  📁 迁移账户：%d 个%n", accountsMigrated);
        System.out.printf("  🗄️  数据库位置：%s%n%n", db.getDbPath());
    }

    // ========================= 迁移用户 =========================

    private int migrateUsers(String dataDir) {
        File usersFile = new File(dataDir + "/users.json");
        if (!usersFile.exists()) {
            log.info("未找到 users.json，跳过用户迁移");
            return 0;
        }

        // 使用 UserStore 的完整初始化（含 DB upsert）
        UserStore store = new UserStore(dataDir);

        int count = 0;
        try {
            JsonNode arr = om.readTree(usersFile);
            if (!arr.isArray()) return 0;

            for (JsonNode n : arr) {
                try {
                    User u = new User();
                    u.setUserId(n.path("userId").asText());
                    u.setUsername(n.path("username").asText());
                    u.setPasswordHash(n.path("passwordHash").asText());
                    u.setNickname(n.path("nickname").asText(""));
                    u.setEmail(n.path("email").asText(""));
                    u.setInitialCapital(n.path("initialCapital").asDouble(100000));
                    try {
                        u.setStrategyType(User.StrategyType.valueOf(n.path("strategyType").asText("DAY_TRADE")));
                    } catch (Exception e) {
                        u.setStrategyType(User.StrategyType.DAY_TRADE);
                    }
                    u.setStrategyConfigJson(n.path("strategyConfigJson").asText(""));
                    u.setWechatSendKey(n.path("wechatSendKey").asText(""));
                    try {
                        u.setStatus(User.UserStatus.valueOf(n.path("status").asText("ACTIVE")));
                    } catch (Exception e) {
                        u.setStatus(User.UserStatus.ACTIVE);
                    }
                    String ct = n.path("createTime").asText("");
                    if (!ct.isEmpty()) {
                        try { u.setCreateTime(LocalDateTime.parse(ct, DT_FMT)); } catch (Exception ignored) {}
                    }
                    String lt = n.path("lastLoginTime").asText("");
                    if (!lt.isEmpty()) {
                        try { u.setLastLoginTime(LocalDateTime.parse(lt, DT_FMT)); } catch (Exception ignored) {}
                    }
                    u.setSuperAdmin(n.path("superAdmin").asBoolean(false));

                    if (u.getUserId() != null && !u.getUserId().isEmpty()) {
                        store.save(u);
                        count++;
                        log.info("  迁移用户: {} ({})", u.getUsername(), u.getUserId());
                    }
                } catch (Exception e) {
                    log.warn("  迁移用户行失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("读取 users.json 失败", e);
        }
        return count;
    }

    // ========================= 迁移账户 =========================

    private int migrateAccounts(String dataDir) {
        AccountPersistence ap = new AccountPersistence(dataDir);
        int count = 0;

        // 迁移根目录下的 auto_trader_main.json（旧的全局账户）
        File rootAccount = new File(dataDir + "/auto_trader_main.json");
        if (rootAccount.exists()) {
            Portfolio p = loadPortfolioFromJson(rootAccount, "auto_trader_main");
            if (p != null) {
                ap.save(p);
                count++;
                log.info("  迁移根账户: auto_trader_main (资金={}, 持仓={}, 订单={})",
                        String.format("%.2f", p.getAvailableCash()),
                        p.getPositions().size(),
                        p.getOrderHistory().size());
            }
        }

        // 迁移每个用户目录下的 auto_trader_main.json
        File usersDir = new File(dataDir + "/users");
        if (usersDir.exists() && usersDir.isDirectory()) {
            File[] userDirs = usersDir.listFiles(File::isDirectory);
            if (userDirs != null) {
                for (File userDir : userDirs) {
                    File accountFile = new File(userDir, "auto_trader_main.json");
                    if (accountFile.exists()) {
                        Portfolio p = loadPortfolioFromJson(accountFile, "auto_trader_main");
                        if (p != null) {
                            // 修改 accountId 以区分用户（userId + "_auto_main"）
                            // 注意：AutoTrader 固定使用 "auto_trader_main" 作为 accountId，
                            // 为保持兼容性，我们保持原 accountId 但在 t_portfolio 中记录 userId
                            // 实际上各用户的 AccountPersistence 都用同一 account_id "auto_trader_main"，
                            // 这会导致冲突。需要将各用户的 accountId 修改为 userId + "_auto_main"
                            String userId = userDir.getName();
                            Portfolio newP = new Portfolio(
                                    userId + "_auto_main",
                                    p.getAccountName(),
                                    p.getInitialCapital(),
                                    p.getMode()
                            );
                            newP.setAvailableCash(p.getAvailableCash());
                            newP.setFrozenCash(p.getFrozenCash());
                            newP.getPositions().putAll(p.getPositions());
                            newP.getOrderHistory().addAll(p.getOrderHistory());
                            ap.save(newP);
                            count++;
                            log.info("  迁移用户账户: {}（accountId={}，资金={}, 持仓={}, 订单={}）",
                                    userId, newP.getAccountId(),
                                    String.format("%.2f", newP.getAvailableCash()),
                                    newP.getPositions().size(),
                                    newP.getOrderHistory().size());
                        }
                    }
                }
            }
        }
        return count;
    }

    /**
     * 从 JSON 文件加载 Portfolio
     */
    private Portfolio loadPortfolioFromJson(File file, String defaultAccountId) {
        try {
            JsonNode root = om.readTree(file);
            String accountId   = root.path("accountId").asText(defaultAccountId);
            String accountName = root.path("accountName").asText("模拟账户");
            double initialCap  = root.path("initialCapital").asDouble(100000);
            double availCash   = root.path("availableCash").asDouble(initialCap);
            double frozenCash  = root.path("frozenCash").asDouble(0);
            Portfolio.AccountMode mode;
            try {
                mode = Portfolio.AccountMode.valueOf(root.path("mode").asText("SIMULATION"));
            } catch (Exception e) {
                mode = Portfolio.AccountMode.SIMULATION;
            }

            Portfolio portfolio = new Portfolio(accountId, accountName, initialCap, mode);
            portfolio.setAvailableCash(availCash);
            portfolio.setFrozenCash(frozenCash);

            // 恢复持仓
            JsonNode posNode = root.path("positions");
            if (posNode.isObject()) {
                posNode.fields().forEachRemaining(entry -> {
                    JsonNode pn = entry.getValue();
                    LocalDate lastBuyDate = null;
                    if (pn.has("lastBuyDate") && !pn.path("lastBuyDate").asText().isEmpty()) {
                        try { lastBuyDate = LocalDate.parse(pn.path("lastBuyDate").asText()); }
                        catch (Exception ignored) {}
                    }
                    LocalDateTime firstBuyTime = LocalDateTime.now();
                    if (pn.has("firstBuyTime") && !pn.path("firstBuyTime").asText().isEmpty()) {
                        try { firstBuyTime = LocalDateTime.parse(pn.path("firstBuyTime").asText(), DT_FMT); }
                        catch (Exception ignored) {}
                    }
                    int quantity     = pn.path("quantity").asInt();
                    int availableQty = pn.path("availableQuantity").asInt();
                    if (lastBuyDate != null && lastBuyDate.equals(LocalDate.now())) {
                        availableQty = 0;
                    }
                    Position pos = Position.builder()
                            .stockCode(pn.path("stockCode").asText())
                            .stockName(pn.path("stockName").asText())
                            .quantity(quantity)
                            .availableQuantity(availableQty)
                            .avgCost(pn.path("avgCost").asDouble())
                            .currentPrice(pn.path("currentPrice").asDouble())
                            .marketValue(pn.path("marketValue").asDouble())
                            .lastBuyDate(lastBuyDate)
                            .firstBuyTime(firstBuyTime)
                            .build();
                    portfolio.getPositions().put(entry.getKey(), pos);
                });
            }

            // 恢复订单历史
            JsonNode ordersNode = root.path("orderHistory");
            if (ordersNode.isArray()) {
                for (JsonNode on : ordersNode) {
                    try {
                        Order.OrderType ot = Order.OrderType.valueOf(on.path("orderType").asText("BUY"));
                        Order.OrderStatus os = Order.OrderStatus.valueOf(on.path("status").asText("FILLED"));
                        LocalDateTime ft = null;
                        if (on.has("filledTime") && !on.path("filledTime").asText().isEmpty()) {
                            try { ft = LocalDateTime.parse(on.path("filledTime").asText(), DT_FMT); }
                            catch (Exception ignored) {}
                        }
                        Order order = Order.builder()
                                .orderId(on.path("orderId").asText())
                                .stockCode(on.path("stockCode").asText())
                                .stockName(on.path("stockName").asText())
                                .orderType(ot)
                                .status(os)
                                .price(on.path("price").asDouble())
                                .quantity(on.path("quantity").asInt())
                                .filledPrice(on.path("filledPrice").asDouble())
                                .filledQuantity(on.path("filledQuantity").asInt())
                                .amount(on.path("amount").asDouble())
                                .commission(on.path("commission").asDouble())
                                .stampTax(on.path("stampTax").asDouble())
                                .transferFee(on.path("transferFee").asDouble())
                                .totalFee(on.path("totalFee").asDouble())
                                .strategyName(on.path("strategyName").asText(""))
                                .remark(on.path("remark").asText(""))
                                .createTime(ft)
                                .filledTime(ft)
                                .build();
                        portfolio.getOrderHistory().add(order);
                    } catch (Exception e) {
                        log.debug("  迁移订单行失败: {}", e.getMessage());
                    }
                }
            }
            return portfolio;
        } catch (Exception e) {
            log.error("读取账户JSON失败: {}", file.getPath(), e);
            return null;
        }
    }
}


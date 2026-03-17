package com.stocktrader.trading;

import com.stocktrader.analysis.AuctionLimitUpScreener;
import com.stocktrader.datasource.StockDataProvider;
import com.stocktrader.model.*;
import com.stocktrader.strategy.AuctionLimitUpStrategy;
import com.stocktrader.util.WechatPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 集合竞价封板策略专用交易调度器
 * <p>
 * 工作流程（每个交易日）：
 * ┌─────────────────────────────────────────────────────┐
 * │  09:15        集合竞价开始，启动竞价选股扫描            │
 * │  09:15~09:25  每30秒扫描一次：封板候选实时更新          │
 * │  09:25        竞价结束，确认最终买入标的                │
 * │  09:30        开盘，执行买入（集合竞价成交价）           │
 * │  09:30~14:50  每分钟检查止损/止盈（快速止损调度）        │
 * │  14:50        强制清仓（不留隔夜）                      │
 * └─────────────────────────────────────────────────────┘
 * <p>
 * 注意：此调度器专供 test01 账户（AUCTION_LIMIT_UP 策略类型），
 * 与其他账户的 AutoTrader 实例完全隔离，互不干扰。
 */
public class AuctionTrader {

    private static final Logger log = LoggerFactory.getLogger(AuctionTrader.class);
    private static final DateTimeFormatter DT_FMT     = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TS    = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter DATE_FMT   = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // 关键时间点
    private static final LocalTime AUCTION_SCAN_START = LocalTime.of(9, 14);  // 提前1分钟就绪
    private static final LocalTime AUCTION_START      = LocalTime.of(9, 15);
    private static final LocalTime AUCTION_END        = LocalTime.of(9, 25);
    private static final LocalTime MARKET_OPEN        = LocalTime.of(9, 30);
    private static final LocalTime FORCE_CLOSE_TIME   = LocalTime.of(14, 50);
    private static final LocalTime MARKET_CLOSE       = LocalTime.of(15, 0);

    // ── 依赖 ──
    private final StockDataProvider    dataProvider;
    private final AuctionLimitUpScreener screener;
    private final AuctionLimitUpStrategy strategy;
    private final AccountPersistence   persistence;
    private final FeeCalculator        feeCalculator;
    private final WechatPushService    pushService;

    // ── 账户配置 ──
    private final String accountId;
    private final String username;
    private final double initialCapital;
    private final String reportDir;
    private final double auctionMinChangeRate;  // 竞价最低涨幅阈值

    // ── 运行状态 ──
    private Portfolio portfolio;
    private volatile boolean stopRequested = false;
    private final AtomicBoolean quickStopRunning = new AtomicBoolean(false);

    // ── 调度器 ──
    private ScheduledExecutorService auctionScheduler;   // 竞价扫描（09:15~09:25）
    private ScheduledExecutorService quickStopScheduler; // 快速止损（09:30~15:00）

    // ── 当日已选封板候选（竞价阶段持续更新，9:25锁定最优标的）──
    private volatile String todayTargetCode = null;
    private volatile String todayTargetName = null;
    private volatile boolean todayBuyExecuted = false;  // 当日是否已执行买入
    /**
     * 当日已主动放弃标志：一旦在锁定窗口（9:24+）判断竞价量不足，设为 true，
     * 后续所有扫描均跳过，不再重新尝试锁定。
     * <p>
     * Bug 场景：9:24:08 量=0放弃 → 9:24:19 新一轮扫描进入，量检查前 todayTargetCode==null，
     * 绕过了"已锁定"判断，重新完成了锁定 → 开盘仍执行买入。
     */
    private volatile boolean todayAbandoned = false;
    // 竞价窗口内持续维护的候选列表（每次扫描都更新，取最优）
    private volatile List<AuctionLimitUpScreener.AuctionCandidate> latestCandidates = new ArrayList<>();

    // ── 统计 ──
    private double cumulativeRealizedPnl = 0;
    private int    totalBuyCount  = 0;
    private int    totalSellCount = 0;
    private int    winSellCount   = 0;

    // 当日基准
    private double dailyStartAssets = -1;
    private LocalDate dailyStartDate = null;

    public AuctionTrader(StockDataProvider dataProvider, double initialCapital,
                         String userId, String username,
                         String accountDir, String reportDir,
                         StrategyConfig sc, String wechatOpenId, String wechatSendKey) {
        this.dataProvider    = dataProvider;
        this.initialCapital  = initialCapital;
        this.accountId       = (userId != null && !userId.isEmpty()) ? userId + "_auto_main" : "auction_trader";
        this.username        = (username != null && !username.isEmpty()) ? username : userId;
        this.reportDir       = reportDir;
        this.auctionMinChangeRate = 0.08; // 默认竞价涨幅≥8%

        this.screener      = new AuctionLimitUpScreener(dataProvider);
        this.strategy      = sc != null ? new AuctionLimitUpStrategy(sc) : new AuctionLimitUpStrategy();
        this.feeCalculator = new FeeCalculator();
        this.persistence   = new AccountPersistence(accountDir);
        // 用户专属微信推送（优先公众号模板消息 openid，降级 Server酱 SendKey）
        this.pushService   = WechatPushService.forUser(wechatOpenId, wechatSendKey);

        // 确保报告目录存在
        new File(reportDir).mkdirs();
        new File(reportDir + "/daily").mkdirs();
        new File(reportDir + "/trades").mkdirs();
    }

    /**
     * 启动竞价封板交易（阻塞运行，由平台线程池调用）
     */
    public void start() {
        log.info("╔══════════════════════════════════════════╗");
        log.info("║   🔔 集合竞价封板策略交易系统启动            ║");
        log.info("║   账户: {}  初始资金: {}元          ║", username, String.format("%.2f", initialCapital));
        log.info("║   策略: {}  ║", strategy.getDescription());
        log.info("╚══════════════════════════════════════════╝");

        // 1. 初始化/恢复账户
        initPortfolio();
        refreshDailyBaseline();

        // 2. 启动竞价扫描调度器（每30秒扫描一次竞价行情）
        startAuctionScanner();

        // 3. 启动快速止损调度器（每30秒检查一次持仓止损）
        startQuickStopScheduler();

        // 4. 阻塞等待停止
        log.info("[竞价封板] 调度器已启动，等待今日竞价窗口（{}~{}）...", AUCTION_START, AUCTION_END);
        try {
            while (!stopRequested) {
                Thread.sleep(5000);
                // 每天收盘后重置当日状态
                LocalTime now = LocalTime.now();
                if (now.isAfter(MARKET_CLOSE)) {
                    resetDailyState();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 停止交易
     */
    public void stopTrading() {
        stopRequested = true;
        if (auctionScheduler != null) auctionScheduler.shutdownNow();
        if (quickStopScheduler != null) quickStopScheduler.shutdownNow();
        persistence.save(portfolio);
        saveDailySummaryReport();
        log.info("[竞价封板] 交易已停止，账户已保存");
    }

    // =================== 调度器启动 ===================

    /**
     * 竞价阶段扫描调度（09:15~09:25，每30秒）
     */
    private void startAuctionScanner() {
        auctionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auction-scanner-" + username);
            t.setDaemon(true);
            return t;
        });
        auctionScheduler.scheduleAtFixedRate(() -> {
            if (stopRequested) return;
            try {
                runAuctionScan();
            } catch (Exception e) {
                log.error("[竞价扫描] 异常: {}", e.getMessage(), e);
            }
        }, 0, 30, TimeUnit.SECONDS);
    }

    /**
     * 快速止损/止盈调度（09:30~15:00，每30秒）
     */
    private void startQuickStopScheduler() {
        quickStopScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auction-quickstop-" + username);
            t.setDaemon(true);
            return t;
        });
        quickStopScheduler.scheduleAtFixedRate(() -> {
            if (stopRequested) return;
            try {
                quickCheckAllPositions();
            } catch (Exception e) {
                log.error("[快速止损] 异常: {}", e.getMessage(), e);
            }
        }, 10, 30, TimeUnit.SECONDS);
    }

    // =================== 核心逻辑 ===================

    /**
     * 竞价阶段扫描：9:15~9:25 内执行选股 + 买入决策
     */
    private void runAuctionScan() {
        LocalTime now = LocalTime.now();
        DayOfWeek dow = LocalDate.now().getDayOfWeek();

        // 周末不执行
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return;

        // 收盘后不执行
        if (now.isAfter(MARKET_CLOSE)) return;

        // T+1 解锁（每日开盘前解锁昨日买入）
        if (now.isBefore(MARKET_OPEN)) {
            portfolio.unlockT1Positions();
        }

        // ===== 竞价窗口（09:15~09:25）：选股 =====
        if (now.isAfter(AUCTION_SCAN_START) && now.isBefore(AUCTION_END)) {
            runAuctionScreening();
            return;
        }

        // ===== 开盘后（09:30~09:45）：执行买入 =====
        // 窗口扩大到 9:45，避免开盘时系统全市场 K 线分析占用导致错过买入
        if (now.isAfter(MARKET_OPEN) && now.isBefore(LocalTime.of(9, 45)) && !todayBuyExecuted) {
            executeTodayBuy();
            return;
        }

        // ===== 尾盘强制清仓（14:50+）=====
        if (now.isAfter(FORCE_CLOSE_TIME)) {
            forceCloseAllPositions("尾盘14:50强制清仓，不留隔夜风险");
        }
    }

    /**
     * 竞价选股（9:15~9:25）：持续更新候选列表，9:24后锁定最优标的
     * <p>
     * 改进逻辑：
     * - 9:15~9:24：每30秒扫描，持续更新Top3候选列表（不锁定，因早期数据不稳定）
     * - 9:24~9:25：竞价结束前锁定窗口，确认最终标的（此时封板意愿最真实）
     * - 一旦锁定（todayTargetCode!=null），后续扫描不再更改
     */
    private void runAuctionScreening() {
        // 当日已有持仓，不重复选股
        if (!portfolio.getPositions().isEmpty()) {
            log.debug("[竞价扫描] 账户已有持仓，跳过选股");
            return;
        }
        // [Bug Fix] 当日已主动放弃（量不足/虚假封单），不再重新锁定
        // 原来只判断 todayTargetCode!=null，放弃时没有置任何标志，导致下一轮扫描又能重新锁定
        if (todayAbandoned) {
            log.debug("[竞价扫描] 今日已判定放弃（竞价量不足），跳过后续扫描");
            return;
        }
        // 已锁定目标，不再更改
        if (todayTargetCode != null) {
            log.debug("[竞价扫描] 今日目标已锁定: {} {}，等待9:30买入", todayTargetCode, todayTargetName);
            return;
        }

        LocalTime now = LocalTime.now();
        // 9:24后进入锁定窗口，确认最终标的（竞价结束前数据最稳定）
        boolean isLockWindow = now.isAfter(LocalTime.of(9, 24));

        log.info("[竞价扫描] {} 开始竞价封板选股{}...", now,
                isLockWindow ? "【锁定窗口，确认最终标的】" : "【持续更新候选】");

        // 取Top3候选，每次扫描都更新
        List<AuctionLimitUpScreener.AuctionCandidate> candidates =
                screener.screenAuctionCandidates(3, auctionMinChangeRate);

        if (candidates.isEmpty()) {
            log.info("[竞价扫描] {} 暂无符合条件的封板候选", now);
            latestCandidates = new ArrayList<>();
            return;
        }

        // 更新候选列表
        latestCandidates = candidates;
        log.info("[竞价扫描] 当前候选列表（Top{}）:", candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            AuctionLimitUpScreener.AuctionCandidate c = candidates.get(i);
            log.info("[竞价扫描]   #{} {} {} 涨幅={}% 量={}手 连板={} 评分={}",
                    i + 1, c.stockCode, c.stockName,
                    String.format("%.2f", c.changeRate * 100),
                    String.format("%.0f", c.auctionVolume / 100),
                    c.consecLimitUp,
                    String.format("%.1f", c.score));
        }

        // 9:24后锁定窗口：确认最终买入标的
        if (isLockWindow) {
            AuctionLimitUpScreener.AuctionCandidate best = candidates.get(0);

            // ===== 竞价量过滤：量为0手时封单可能是虚假挂单，放弃买入 =====
            // 竞价阶段若成交量为0手，意味着目前无实际撮合成交，涨停价挂单纯粹是方向性意愿，
            // 极易在9:30开盘瞬间遭到砸盘而破板，此类标的不应买入。
            double auctionVolHands = best.auctionVolume / 100.0;
            if (auctionVolHands < 1.0) {
                log.warn("[竞价选股] ✗ {} {} 竞价量={}手（不足1手），封单可能是虚假挂单，本日放弃封板买入！" +
                                "（量不足时开盘破板风险极高）",
                        best.stockCode, best.stockName, String.format("%.0f", auctionVolHands));
                pushService.sendMessage("⚠️ 竞价封板放弃 - " + username,
                        String.format("标的：%s %s\n原因：竞价量=%.0f手（<1手），封单疑似虚假，放弃买入",
                                best.stockCode, best.stockName, auctionVolHands));
                // [Bug Fix] 设置 todayAbandoned=true，阻止后续扫描（每30秒一次）重新进入锁定逻辑
                // 原来只清空 latestCandidates 并 return，但下一轮扫描 todayTargetCode 仍为 null，
                // 会重新完成选股→锁定→开盘买入，造成"放弃后仍买入"的逻辑漏洞
                todayAbandoned = true;
                latestCandidates = new ArrayList<>();
                return;
            }

            todayTargetCode = best.stockCode;
            todayTargetName = best.stockName;

            log.info("[竞价选股] ✦✦ 锁定今日封板目标: {} {} 竞价涨幅={}% 量={}手 评分={} 将在9:30买入",
                    todayTargetCode, todayTargetName,
                    String.format("%.2f", best.changeRate * 100),
                    String.format("%.0f", auctionVolHands),
                    String.format("%.1f", best.score));

            // 微信推送选股通知（包含候选列表）
            StringBuilder candidateInfo = new StringBuilder();
            for (int i = 0; i < candidates.size(); i++) {
                AuctionLimitUpScreener.AuctionCandidate c = candidates.get(i);
                candidateInfo.append(String.format("\n%s%d. %s %s 涨%.2f%% 评分%.1f",
                        i == 0 ? "★" : "  ", i + 1,
                        c.stockCode, c.stockName, c.changeRate * 100, c.score));
            }
            pushService.sendMessage("🔔 竞价封板选股锁定 - " + username,
                    String.format("最终标的：%s %s\n竞价涨幅：%.2f%%\n量（手）：%.0f\n连板：%d\n评分：%.1f\n候选列表：%s\n→ 9:30买入",
                            best.stockCode, best.stockName,
                            best.changeRate * 100,
                            best.auctionVolume / 100,
                            best.consecLimitUp, best.score,
                            candidateInfo.toString()));
        }
    }

    /**
     * 9:30开盘后执行今日封板目标的买入
     */
    private void executeTodayBuy() {
        if (todayTargetCode == null) {
            log.debug("[开盘买入] 今日无封板目标，跳过");
            return;
        }
        if (todayBuyExecuted) return;
        if (!portfolio.getPositions().isEmpty()) {
            log.debug("[开盘买入] 已有持仓，跳过重复买入");
            todayBuyExecuted = true;
            return;
        }

        String code = todayTargetCode;
        String name = todayTargetName;

        // 获取开盘实时价
        StockBar rt = dataProvider.getRealTimeQuote(code);
        if (rt == null || rt.getClose() <= 0) {
            log.warn("[开盘买入] {} 获取实时价失败，跳过买入", code);
            return;
        }
        double price = rt.getClose();

        // 再次验证开盘价仍在封板区间（防止虚假竞价，开盘即破板 / 高开超涨）
        // 从最近的K线中获取昨收
        try {
            LocalDate today = LocalDate.now();
            List<StockBar> bars = dataProvider.getDailyBars(
                    code, today.minusDays(5), today, StockBar.AdjustType.NONE);
            if (bars != null && bars.size() >= 2) {
                double prevClose = bars.get(bars.size() - 2).getClose();
                boolean isKeChuang = code.startsWith("688") || code.startsWith("300");
                double limitUpRate  = isKeChuang ? 1.20 : 1.10;
                double limitUpPrice = prevClose * limitUpRate;

                // [Bug Fix 1] 开盘价低于昨收×97%：竞价可能虚假，开盘跌停或大跌，放弃
                if (price < prevClose * 0.97) {
                    log.warn("[开盘买入] ✗ {} 开盘价={}，低于昨收×97%（昨收={}），开盘跌幅过大，放弃买入",
                             code, String.format("%.2f", price), String.format("%.2f", prevClose));
                    todayTargetCode = null;
                    todayAbandoned = true;
                    return;
                }

                // [Bug Fix 2] 开盘价超过涨停价×102%（即高开超过涨停价）：
                // 说明竞价阶段集中大量买单推高价格，开盘已超涨停位，继续追入风险极高，放弃
                // 正常封板买入应该：开盘价 ≈ 涨停价（±2%容差）
                if (price > limitUpPrice * 1.02) {
                    log.warn("[开盘买入] ✗ {} 开盘价={}，超过涨停价×102%（涨停价={}），高开超涨，放弃追涨买入！",
                             code, String.format("%.2f", price), String.format("%.2f", limitUpPrice));
                    todayTargetCode = null;
                    todayAbandoned = true;
                    return;
                }

                log.info("[开盘买入] {} {} 开盘价={}，涨停价={}（{}%），价格验证通过，执行买入...",
                         code, name,
                         String.format("%.2f", price),
                         String.format("%.2f", limitUpPrice),
                         String.format("%.0f", (limitUpRate - 1) * 100));
            }
        } catch (Exception e) {
            log.debug("[开盘买入] 获取K线验证失败: {}，继续执行买入", e.getMessage());
        }

        // 计算买入数量（用全仓资金，至少100股）
        double buyRatio  = strategy.calculatePositionSize(null, portfolio);
        double buyAmount = portfolio.getAvailableCash() * buyRatio;
        int quantity = (int)(buyAmount / price / 100) * 100;
        if (quantity == 0 && portfolio.getAvailableCash() >= price * 100) {
            quantity = (int)(portfolio.getAvailableCash() / price / 100) * 100;
        }
        if (quantity <= 0) {
            log.warn("[开盘买入] {} 资金不足（可用={}，价格={}）", code, String.format("%.2f", portfolio.getAvailableCash()), String.format("%.2f", price));
            todayBuyExecuted = true;
            return;
        }

        double amount = quantity * price;
        String exchange = code.startsWith("6") ? "SH" : "SZ";
        FeeCalculator.FeeDetail fee = feeCalculator.calculateBuyFee(amount, exchange);

        String buyReason = String.format("竞价封板策略买入：封板目标=%s，开盘价=%.2f，全仓买入%.0f%%", name, price, buyRatio * 100);
        Order order = Order.builder()
                .orderId(UUID.randomUUID().toString())
                .stockCode(code)
                .stockName(name)
                .orderType(Order.OrderType.BUY)
                .status(Order.OrderStatus.FILLED)
                .price(price).quantity(quantity)
                .filledPrice(price).filledQuantity(quantity)
                .amount(amount)
                .commission(fee.commission).stampTax(0)
                .transferFee(fee.transferFee).totalFee(fee.total)
                .createTime(LocalDateTime.now()).filledTime(LocalDateTime.now())
                .strategyName(strategy.getStrategyName())
                .remark(buyReason)
                .build();

        boolean ok = portfolio.executeBuy(order);
        if (ok) {
            todayBuyExecuted = true;
            totalBuyCount++;
            saveTradeReportFile("买入", order, buyReason, null, 0, 0);
            persistence.save(portfolio);
            log.info("[开盘买入] ✅ {} {} 买入成功：{}股 @{} 金额={}",
                    code, name, quantity, String.format("%.2f", price), String.format("%.2f", amount));
            pushService.sendBuySignal(username, code, name, price, quantity, amount, buyReason,
                    portfolio.getTotalAssets(), portfolio.getAvailableCash(), buildPositionSummary());
        } else {
            log.warn("[开盘买入] {} 买入失败（portfolio.executeBuy返回false）", code);
        }
    }

    /**
     * 快速止损/止盈检查（每30秒）
     */
    private void quickCheckAllPositions() {
        LocalTime now = LocalTime.now();
        DayOfWeek dow = LocalDate.now().getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return;
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) return;

        if (quickStopRunning.getAndSet(true)) return;
        try {
            Map<String, Position> positions = portfolio.getPositions();
            if (positions.isEmpty()) return;

            for (String code : new ArrayList<>(positions.keySet())) {
                if (stopRequested) break;
                try {
                    checkOnePosition(code, now);
                } catch (Exception e) {
                    log.warn("[快速止损] 检查 {} 失败: {}", code, e.getMessage());
                }
            }
        } finally {
            quickStopRunning.set(false);
        }
    }

    /**
     * 检查单只持仓的止损/止盈/强制清仓
     */
    private void checkOnePosition(String code, LocalTime now) {
        Position pos = portfolio.getPosition(code);
        if (pos == null || pos.getAvailableQuantity() <= 0) return;

        StockBar rt = dataProvider.getRealTimeQuote(code);
        if (rt == null || rt.getClose() <= 0) return;

        double currentPrice = rt.getClose();
        double avgCost = pos.getAvgCost();
        if (avgCost <= 0) return;

        portfolio.updatePositionPrice(code, currentPrice);
        double profitRate = (currentPrice - avgCost) / avgCost;

        // 1. 尾盘强制清仓
        if (now.isAfter(FORCE_CLOSE_TIME)) {
            log.info("[尾盘清仓] {} 14:50强制平仓，盈亏={}%", code, String.format("%.2f", profitRate * 100));
            executeSell(code, pos.getStockName(), currentPrice, pos.getAvailableQuantity(),
                    String.format("竞价封板尾盘强制清仓（14:50），盈亏=%.2f%%", profitRate * 100), false);
            return;
        }

        // 2. 获取昨收来计算涨停价（用于破板判断）
        boolean isKeChuang = code.startsWith("688") || code.startsWith("300");
        // 以买入成本近似估计昨收（竞价涨幅~8~10%，昨收≈成本/1.09）
        double estPrevClose = avgCost / (isKeChuang ? 1.18 : 1.09);
        double limitUpPrice = estPrevClose * (isKeChuang ? 1.20 : 1.10);

        // 3. 次日开盘30分钟紧止损（2%）
        boolean isNextDayAfterBuy = pos.getLastBuyDate() != null
                && pos.getLastBuyDate().isBefore(LocalDate.now());
        boolean inOpeningWindow = now.isAfter(LocalTime.of(9, 30))
                && now.isBefore(LocalTime.of(10, 0));
        double effectiveStopLoss = (isNextDayAfterBuy && inOpeningWindow)
                ? 0.02 : strategy.getStopLossPercent();

        // 4. 破板止损（现价 < 涨停价×97%）
        boolean isBroken   = currentPrice < limitUpPrice * 0.97;
        boolean isStopLoss = profitRate <= -effectiveStopLoss;

        if (isBroken || isStopLoss) {
            String reason = isBroken
                    ? String.format("[竞价封板破板止损] 现价=%.2f < 涨停价%.2f×97%%=%.2f，盈亏=%.2f%%",
                            currentPrice, limitUpPrice, limitUpPrice * 0.97, profitRate * 100)
                    : String.format("[竞价封板止损] 成本=%.2f 现价=%.2f 亏损=%.2f%%（止损线%.0f%%）",
                            avgCost, currentPrice, profitRate * 100, effectiveStopLoss * 100);
            log.warn("[止损触发] {} {}", code, reason);
            executeSell(code, pos.getStockName(), currentPrice, pos.getAvailableQuantity(), reason, false);
            return;
        }

        // 5. 止盈（盈利达到目标）
        if (profitRate >= strategy.getTakeProfitPercent()) {
            String reason = String.format("[竞价封板止盈] 成本=%.2f 现价=%.2f 盈利=%.2f%%（目标=%.0f%%）",
                    avgCost, currentPrice, profitRate * 100, strategy.getTakeProfitPercent() * 100);
            log.info("[止盈触发] {} {}", code, reason);
            executeSell(code, pos.getStockName(), currentPrice, pos.getAvailableQuantity(), reason, true);
        }
    }

    /**
     * 强制清仓所有持仓（收盘前调用）
     */
    private void forceCloseAllPositions(String reason) {
        for (String code : new ArrayList<>(portfolio.getPositions().keySet())) {
            Position pos = portfolio.getPosition(code);
            if (pos == null || pos.getAvailableQuantity() <= 0) continue;
            StockBar rt = dataProvider.getRealTimeQuote(code);
            double price = (rt != null && rt.getClose() > 0)
                    ? rt.getClose() : pos.getCurrentPrice();
            if (price <= 0) price = pos.getAvgCost();
            executeSell(code, pos.getStockName(), price, pos.getAvailableQuantity(),
                    reason + "：" + code, false);
        }
    }

    /**
     * 执行卖出
     */
    private void executeSell(String code, String name, double price, int quantity,
                              String reason, boolean isTakeProfit) {
        Position pos = portfolio.getPosition(code);
        if (pos == null || pos.getAvailableQuantity() <= 0) return;
        if (quantity <= 0) quantity = pos.getAvailableQuantity();

        double amount = quantity * price;
        String exchange = code.startsWith("6") ? "SH" : "SZ";
        FeeCalculator.FeeDetail fee = feeCalculator.calculateSellFee(amount, exchange);
        double pnl = (price - pos.getAvgCost()) * quantity - fee.total;
        cumulativeRealizedPnl += pnl;
        if (pnl > 0) winSellCount++;

        Order order = Order.builder()
                .orderId(UUID.randomUUID().toString())
                .stockCode(code).stockName(name != null ? name : code)
                .orderType(Order.OrderType.SELL)
                .status(Order.OrderStatus.FILLED)
                .price(price).quantity(quantity)
                .filledPrice(price).filledQuantity(quantity)
                .amount(amount)
                .commission(fee.commission).stampTax(fee.stampTax)
                .transferFee(fee.transferFee).totalFee(fee.total)
                .createTime(LocalDateTime.now()).filledTime(LocalDateTime.now())
                .strategyName(strategy.getStrategyName())
                .remark(reason)
                .build();

        double pnlRate = pos.getAvgCost() > 0 ? (price - pos.getAvgCost()) / pos.getAvgCost() * 100 : 0;
        boolean ok = portfolio.executeSell(order);
        if (ok) {
            totalSellCount++;
            saveTradeReportFile("卖出", order, reason, null, pnl, pnlRate);
            persistence.save(portfolio);
            log.info("[卖出成交] {} {} {}股 @{} 盈亏={}元 ({}%)",
                    code, name, quantity, String.format("%.2f", price),
                    String.format("%+.2f", pnl), String.format("%+.2f", pnlRate));
            double totalReturn = (portfolio.getTotalAssets() - initialCapital) / initialCapital * 100;
            pushService.sendSellSignal(username, code, name != null ? name : code,
                    price, pos.getAvgCost(), quantity, amount, pnl, pnlRate,
                    reason, totalReturn, buildPositionSummary());
        }
    }

    // =================== 辅助方法 ===================

    private void initPortfolio() {
        Portfolio loaded = persistence.load(accountId);
        if (loaded != null) {
            this.portfolio = loaded;
            log.info("[竞价封板] 已恢复账户：可用资金={}，持仓{}只",
                    String.format("%.2f", portfolio.getAvailableCash()), portfolio.getPositions().size());
        } else {
            this.portfolio = new Portfolio(accountId, "竞价封板账户",
                    initialCapital, Portfolio.AccountMode.SIMULATION);
            log.info("[竞价封板] 创建新账户，初始资金={}元", String.format("%.2f", initialCapital));
            persistence.save(portfolio);
        }
    }

    private void refreshDailyBaseline() {
        LocalDate today = LocalDate.now();
        if (dailyStartDate == null || !dailyStartDate.equals(today)) {
            dailyStartDate  = today;
            dailyStartAssets = portfolio.getTotalAssets();
            log.info("[竞价封板] 今日基准资产={}元", String.format("%.2f", dailyStartAssets));
        }
    }

    /**
     * 重置当日状态（收盘后调用）
     */
    private void resetDailyState() {
        if (dailyStartDate != null && dailyStartDate.equals(LocalDate.now())) {
            return; // 已重置
        }
        todayTargetCode   = null;
        todayTargetName   = null;
        todayBuyExecuted  = false;
        todayAbandoned    = false;  // 每日重置放弃标志，新的一天可以重新选股
        latestCandidates  = new ArrayList<>();
        refreshDailyBaseline();
        log.info("[竞价封板] 新的一天（{}），当日状态已重置，等待明日竞价窗口", LocalDate.now());
    }

    private String buildPositionSummary() {
        if (portfolio.getPositions().isEmpty()) return "当前空仓";
        StringBuilder sb = new StringBuilder();
        portfolio.getPositions().forEach((code, pos) -> {
            double pct = pos.getAvgCost() > 0
                    ? (pos.getCurrentPrice() - pos.getAvgCost()) / pos.getAvgCost() * 100 : 0;
            sb.append(String.format("%s %s %d股 盈亏%+.1f%%  ",
                    code, pos.getStockName() != null ? pos.getStockName() : code,
                    pos.getQuantity(), pct));
        });
        return sb.toString().trim();
    }

    private void saveTradeReportFile(String action, Order order, String reason,
                                      Object ignored, double pnl, double pnlRate) {
        String ts = LocalDateTime.now().format(FILE_TS);
        String filename = String.format("%s/trades/trade_%s_%s_%s.txt",
                reportDir, action, order.getStockCode(), ts);
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename, false))) {
            pw.println("══════════════════════════════════════════════════════");
            pw.printf("  【竞价封板策略 - %s成交报告】%n", action);
            pw.println("══════════════════════════════════════════════════════");
            pw.printf("  股票代码: %s%n", order.getStockCode());
            pw.printf("  股票名称: %s%n", order.getStockName());
            pw.printf("  交易类型: %s%n", action);
            pw.printf("  成交时间: %s%n", LocalDateTime.now().format(DT_FMT));
            pw.printf("  成交价格: %.2f 元%n", order.getFilledPrice());
            pw.printf("  成交数量: %d 股（%d 手）%n", order.getFilledQuantity(), order.getFilledQuantity() / 100);
            pw.printf("  成交金额: %,.2f 元%n", order.getAmount());
            pw.printf("  手续费:   %.2f 元%n", order.getTotalFee());
            pw.printf("  触发原因: %s%n", reason != null ? reason : "");
            if (pnl != 0) {
                pw.printf("  本次盈亏: %+.2f 元（%+.2f%%）%n", pnl, pnlRate);
            }
            pw.printf("  账户总资产: %.2f 元%n", portfolio.getTotalAssets());
            pw.printf("  可用资金:   %.2f 元%n", portfolio.getAvailableCash());
            pw.println("══════════════════════════════════════════════════════");
        } catch (Exception e) {
            log.warn("[竞价封板] 保存交易报告失败: {}", e.getMessage());
        }
        log.info("[竞价封板] 交易报告已保存: {}", filename);
    }

    private void saveDailySummaryReport() {
        String date = LocalDate.now().format(DATE_FMT);
        String filename = String.format("%s/daily/daily_%s.txt", reportDir, date);
        double totalAssets = portfolio.getTotalAssets();
        double dailyPnl    = dailyStartAssets > 0 ? totalAssets - dailyStartAssets : 0;
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename, false))) {
            pw.println("══════════════════════════════════════════════════════");
            pw.printf("  竞价封板策略 - 日报 %s%n", date);
            pw.println("══════════════════════════════════════════════════════");
            pw.printf("  初始资金:   %.2f 元%n", initialCapital);
            pw.printf("  总资产:     %.2f 元%n", totalAssets);
            pw.printf("  当日盈亏:   %+.2f 元%n", dailyPnl);
            pw.printf("  今日目标:   %s %s%n",
                    todayTargetCode != null ? todayTargetCode : "无",
                    todayTargetName != null ? todayTargetName : "");
            pw.printf("  买入次数:   %d%n", totalBuyCount);
            pw.printf("  卖出次数:   %d%n", totalSellCount);
            pw.printf("  历史胜率:   %.1f%% (%d/%d)%n",
                    totalSellCount > 0 ? (double) winSellCount / totalSellCount * 100 : 0,
                    winSellCount, totalSellCount);
        } catch (Exception e) {
            log.warn("[竞价封板] 保存日报失败: {}", e.getMessage());
        }
    }
}


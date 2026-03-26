package com.stocktrader.trading;

import com.stocktrader.analysis.DayTradingStockScreener;
import com.stocktrader.analysis.StockAnalyzer;
import com.stocktrader.analysis.StockScreener;
import com.stocktrader.config.SystemConfig;
import com.stocktrader.datasource.StockDataProvider;
import com.stocktrader.model.*;
import com.stocktrader.strategy.DayTradingStrategy;
import com.stocktrader.strategy.IntradayTradingStrategy;
import com.stocktrader.strategy.MediumLongTermStrategy;
import com.stocktrader.strategy.TradingStrategy;
import com.stocktrader.util.TradeCallbackServer;
import com.stocktrader.util.WechatPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 全自动交易监控器
 * <p>
 * 功能：
 * 1. 启动时全市场扫描选出 Top N 股票加入监控池
 * 2. 工作日盘中（9:30~15:00）每隔指定分钟扫描实时行情
 * 3. 触发信号时自动模拟买卖，打印收益报告
 * 4. 账户状态持久化（程序重启后自动恢复）
 * 5. 支持手动刷新选股池（更换持仓标的）
 */
public class AutoTrader {

    private static final Logger log = LoggerFactory.getLogger(AutoTrader.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    // 交易报告目录
    private String reportDir;

    // 交易时段
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 0);
    private static final LocalTime NOON_BREAK_START = LocalTime.of(11, 30);
    private static final LocalTime NOON_BREAK_END = LocalTime.of(13, 0);

    private final StockDataProvider dataProvider;
    private final StockScreener screener;
    private final StockAnalyzer analyzer;
    private final FeeCalculator feeCalculator;
    private final AccountPersistence persistence;
    private final TradingStrategy strategy;
    /**
     * [P2-3 优化] 策略配置对象引用，用于运行时读取可配置参数（如追踪止盈回撤容忍度）。
     * 仅用于读取配置，不修改策略状态。
     */
    private final com.stocktrader.model.StrategyConfig strategyConfig;

    private Portfolio portfolio;
    private List<String> watchlist = new ArrayList<>();       // 当前监控池（选出的股票）
    private List<StockScreener.ScreenResult> screenResults = new ArrayList<>();  // 选股结果

    // 配置
    private final double initialCapital;
    private final int topN;              // 选股Top N
    private final int minScore;          // 选股最低分
    private volatile int scanIntervalMin;   // 扫描间隔（分钟），支持运行时动态调整
    /**
     * 秒级扫描间隔（秒）
     * > 0 时优先使用秒级调度（适合日内做T高频策略）
     * = 0 时使用 scanIntervalMin（分钟级调度）
     */
    private final int scanIntervalSeconds;
    private final String dataDir;        // 账户持久化目录
    private final String accountId;      // 数据库中的账户ID（单机=auto_trader_main，多用户=userId_auto_main）
    private final String username;       // 用户名（用于微信推送中显示账户名称）

    /**
     * 日内做T专用选股器（仅当策略为 IntradayTradingStrategy 时启用）
     * 用于每日开盘前/盘中重新选出最适合做T的1支股票
     */
    private final DayTradingStockScreener dayTradingScreener;

    // 推送与回调服务
    private final WechatPushService pushService;
    private TradeCallbackServer callbackServer;

    // 多用户模式下的停止标志
    private volatile boolean stopRequested = false;
    private ScheduledExecutorService schedulerRef;
    // 快速止损调度器（每分钟，仅拉实时价）
    private ScheduledExecutorService quickStopScheduler;
    // 防止快速止损与全量扫描同时执行同一股票
    private final AtomicBoolean quickStopRunning = new AtomicBoolean(false);
    /**
     * [P0-2 优化] 交易操作全局锁：统一保护快速止损与全量扫描，彻底消除重复卖出竞态
     * - runOneScan() 与 quickStopLossCheck() 使用同一把锁，互斥执行
     * - 快速止损尝试 tryLock(0) ：正在全量扫描时即刻放弃本次快速止损（全量扫描内已包含止损逻辑）
     * - 全量扫描尝试 tryLock(100ms) ：给正在执行的快速止损一点缓充时间结束，再继续
     */
    private final ReentrantLock tradeLock = new ReentrantLock(true); // fair=true，防饥死

    // 已实现盈亏累计
    private double cumulativeRealizedPnl = 0;
    private int totalBuyCount = 0;
    private int totalSellCount = 0;
    private int winSellCount = 0;

    // ===== 最大回撤追踪 =====
    /** 运行以来的账户总资产历史峰值（用于计算最大回撤）*/
    private double peakTotalAssets = -1;
    /** 最大回撤值（元）：运行以来最大的从峰值到谷值的绝对亏损额 */
    private double maxDrawdownAbs = 0;
    /** 最大回撤比率（%）：maxDrawdownAbs / peakTotalAssets * 100 */
    private double maxDrawdownPct = 0;

    // ===== 当日盈亏基准快照 =====
    /** 当日开始时的总资产快照（用于计算当日盈亏） */
    private double dailyStartAssets = -1;
    /** 快照对应的日期（跨日时自动重置） */
    private LocalDate dailyStartDate = null;

    /**
     * 本轮扫描中被持仓上限阻止的强烈买入信号（每次 runOneScan 开始时清空）
     * key=stockCode, value=[signal, analysis]
     */
    private final Map<String, Object[]> blockedStrongBuySignals = new LinkedHashMap<>();

    // ===== 仓位置换配置 =====
    /** 新股信号强度必须达到此值才有资格触发置换（默认85，即 STRONG_BUY 且信号很强）*/
    private static final int SWAP_MIN_NEW_STRENGTH    = 85;
    /** 新股综合评分比最弱持仓评分高出此值才置换（防止频繁换仓）
     * 【P2-2优化】从20分提高到30分，历史数据分析显示评分差<30的换股多数无效且浪费手续费。
     * 只有评分差距足够大（≥30分）的换股操作，才能在扣除双边手续费后仍然产生正期望。
     */
    private static final int SWAP_MIN_SCORE_ADVANTAGE = 30;
    /**
     * 被置换的持仓最大亏损容忍（超过此亏损率不置换，避免深度割肉）。
     * 设为 -3%：亏损在0~-3% 之间可换，更大亏损由止损逻辑处理。
     * 【优化】从-5%收紧到-3%，避免将深度亏损仓位作为置换匹配
     */
    private static final double SWAP_MAX_LOSS_RATE    = -0.03; // -3%
    /** 每轮最多执行置换次数（避免全部仓位在一轮内都被换掉，上限 topN/2）*/
    private static final int SWAP_MAX_PER_ROUND       = 2;
    /** 换仓冷却期（毫秒）：同一只股票换出后此时间内不允许再次换入，防止反复换手 */
    private static final long SWAP_COOLDOWN_MS        = 24 * 60 * 60 * 1000L; // 24小时

    /**
     * 满仓时定频大盘扫描配置：
     * 每隔 FULL_SCAN_EVERY_N_ROUNDS 次 runOneScan 执行一次全市场扫描（即便已满仓）。
     * 目的是发现评分更高的新候选，配合 tryPositionSwap 实现「汰弱留强」。
     * 默认每 3 轮（≈30分钟）扫一次大盘，避免每轮都扫拖慢速度。
     */
    private static final int FULL_SCAN_EVERY_N_ROUNDS = 3;
    /** 当前已执行的扫描轮次计数（用于定频触发全市场扫描）*/
    private int scanRoundCount = 0;

    /**
     * 持仓评分缓存：key=stockCode, value=最新技术评分。
     * 在 refreshWatchlistForSwap() 时同步更新持仓股的评分，
     * 避免 tryPositionSwap 用过期的 screenResults 来评估持仓强弱。
     */
    private final Map<String, Integer> holdingScoreCache = new HashMap<>();

    /**
     * 换仓冷却期记录：key=stockCode（已换出的股票代码），value=换出时间戳（ms）。
     * 用于防止同一只股在冷却期内反复被换入换出，减少无谓手续费损耗。
     */
    private final Map<String, Long> swapCooldownMap = new HashMap<>();

    /**
     * 换入候选股连续达标交易日数：key=stockCode, value=连续上榜的【不同交易日】数量。
     * 若某只股票连续达标天数 < SWAP_MIN_QUALIFY_DAYS，则不允许作为换入目标。
     * 注意：同一交易日内多次扫描只计1次，防止单日扫描次数影响计数。
     */
    private final Map<String, Integer> candidateQualifyDays = new HashMap<>();
    /**
     * 候选股当日已被记录过的交易日标记集合：存储 "stockCode_yyyy-MM-dd" 形式的键。
     * 用于保证同一交易日内对同一候选股只累加一次达标天数。
     * 每个新交易日开始时（refreshDailyBaseline）自动清空。
     */
    private final Set<String> candidateQualifyDaySeen = new java.util.HashSet<>();
    /**
     * 换入候选股连续达标最少交易日数（[P2-2 优化] 由2天提升至3天）
     * <p>
     * 原因：2天达标门槛过低，偶然拉升一两天即可触发换仓，导致：
     *   1. 换仓后标的往往已是短期高点（追涨），缺乏上涨空间
     *   2. 手续费（双向约0.25%）侵蚀换仓收益
     * 提升到3天：候选股需连续3个交易日评分达标才允许换入，
     *   确保是真正的持续强势而非短暂脉冲，降低"追高后回调"的换仓损耗。
     */
    private static final int SWAP_MIN_QUALIFY_DAYS = 3;

    // ===== 大盘择时过滤 =====
    /** 上证指数代码（用于大盘趋势判断） */
    private static final String MARKET_INDEX_CODE = "000001";
    /**
     * [P2-2 优化] 沪深300指数代码，用于与上证指数做交叉验证。
     * 逻辑：仅当上证指数 MA5<MA20 同时 沪深300 MA5<MA20 时，才认定为弱势。
     * 若两者只有一个满足（如上证震荡但沪深300仍较强），则不触发弱势拦截，降低误触发率。
     */
    private static final String CSI300_INDEX_CODE = "000300";
    /** 大盘当日跌幅超过此值时暂停全部新开仓（-1.5%，系统性风险高）*/
    private static final double MARKET_DROP_THRESHOLD = -0.015;
    /**
     * MA5<MA20（空头排列/震荡弱势）时，仍允许通过的信号最低强度阈值（85分）。
     * <p>
     * 分级策略：
     *   - 大盘当日跌幅 >= 1.5%：全面封禁，所有新开仓（含加仓）均停止
     *   - 大盘 MA5<MA20 但跌幅 < 1.5%（震荡偏弱）：
     *       ✓ 信号强度 >= 85 的强烈买入信号允许通过（个股趋势独立于大盘，不应全锁）
     *       ✗ 信号强度 < 85 的普通买入信号继续拦截
     * 目的：避免 MA5<MA20 在 A 股震荡行情中长期满足而导致全天零买入，
     *       同时保留对系统性大跌的全面防护。
     * </p>
     */
    private static final int MARKET_BEARISH_STRONG_SIGNAL_THRESHOLD = 85;
    /** 大盘MA5下穿MA20（空头排列）时暂停新开仓标志 */
    private volatile boolean marketBearish = false;
    /**
     * [P0-2] 大盘情绪综合评分（0~100）：
     * 综合涨跌家数比、全市场成交量两个维度评估大盘情绪强弱。
     * 分数越高表示市场情绪越积极，分数越低表示情绪越悲观。
     * 门槛：评分 < 35 时，自动将买入信号的阈值从85提高到90（更严格的过滤）。
     */
    private volatile int marketSentimentScore = 50; // 默认中性
    /**
     * [P0-2 优化] 深度熊市标志：MA5 < MA20 < MA60（三线空头排列）
     * <p>
     * 区别于普通弱势（marketBearish，MA5<MA20）：
     *   - 普通弱势（MA5<MA20）：仅拦截低质量新开仓（强度<85的信号被拦），允许换仓
     *   - 深度熊市（MA5<MA20<MA60）：同时禁止换仓操作（tryPositionSwap 返回），
     *     因为换仓会先卖出持仓（锁定亏损），再买入新股（高概率继续亏损），
     *     在趋势性下跌行情中极易形成"越换越亏"的死亡螺旋。
     * <p>
     * 解锁条件：MA5 >= MA20 或 MA20 >= MA60（任意一条均线恢复正排列）。
     */
    private volatile boolean marketDeepBearish = false;
    /** 大盘择时状态最后更新时间（毫秒，避免每次买入都重新拉行情）*/
    private volatile long marketStatusLastUpdate = 0L;
    /** 大盘择时状态缓存有效期（5分钟）*/
    private static final long MARKET_STATUS_CACHE_MS = 5 * 60 * 1000L;
    /** 最近一次缓存的大盘指数涨跌幅（%，用于 MA5<MA20 场景中区分「大跌」和「空头排列」）*/
    private volatile double cachedIndexChangePct = 0.0;

    // ===== 盘后自动参数优化 =====
    /** 今日是否已触发过盘后参数优化（防止同一天重复触发）*/
    private volatile boolean postMarketOptRunToday = false;

    // ===== 止损冷却机制 =====
    /**
     * 止损冷却表：key=账户级标志位（固定key）, value=最近一次止损卖出的时间戳（ms）。
     * 止损触发后 STOP_LOSS_COOLDOWN_MS 内禁止任何新开仓，防止「止损→追高→再止损」死循环。
     */
    private volatile long lastStopLossTimeMs = 0L;
    /** 止损后新开仓冷却期（默认2小时）*/
    private static final long STOP_LOSS_COOLDOWN_MS = 1 * 60 * 60 * 1000L; // 1小时（P0-1优化：由2h缩短为1h，避免开盘止损后浪费整个上午）

    // ===== 止损后同标的重入保护 =====
    /**
     * 止损股票重入保护表：key=stockCode，value=止损发生的交易日（LocalDate）。
     * <p>
     * 某只股票触发止损后，记录其止损日期。当该股票重新出现买入信号时：
     *   - 若距止损日不足 {@link #STOP_LOSS_REENTRY_MIN_DAYS} 个交易日，则拒绝重入。
     *   - 超过保护期后才允许买入，防止「止损→次日追回→再次止损」循环。
     * <p>
     * 注意：此保护针对具体股票代码，独立于账户级别止损冷却（STOP_LOSS_COOLDOWN_MS）。
     * 两者共同作用：账户级冷却是短期（1小时内禁止任何开仓），股票级保护是中期（至少2日）。
     */
    private final Map<String, LocalDate> stopLossReentryMap = new HashMap<>();
    /** 止损后同一标的重入所需的最少不同交易日数（默认2日） */
    private static final int STOP_LOSS_REENTRY_MIN_DAYS = 2;

    // ===== 账户级日止损熔断 =====
    /**
     * 当日账户亏损触发熔断阈值（默认 -2%）。
     * 当日总资产较当日基准亏损超过此比例时，触发账户级熔断：
     *   - 所有新开仓/加仓信号均被拦截，当日不再买入。
     *   - 止损卖出不受影响，保障风险出清。
     * 每个新交易日（refreshDailyBaseline 检测到新日期）时自动解除。
     */
    private static final double DAILY_LOSS_CIRCUIT_BREAKER_PCT = -0.02; // -2%
    /**
     * 熔断状态标志：true 表示今日已熔断，禁止任何新开仓/加仓。
     * 每个新交易日开始时（refreshDailyBaseline）自动重置为 false。
     */
    private volatile boolean dailyCircuitBroken = false;

    // ===== [P3-1 优化] 大亏后次日冷却期 =====
    /**
     * 大亏触发阈值（-3%）：当日账户亏损超过此比例时，次日进入「半冷却期」。
     * <p>
     * 半冷却期特征（次日全天生效）：
     *   - 只允许评分 >= 80 的高质量信号开仓（普通信号60~79分被拦截）
     *   - 止损/止盈/追踪止盈等清仓操作不受影响
     * <p>
     * 冷却期机制的意义：
     *   - 大亏通常发生在行情突变或选股系统性偏差时，需要时间让市场情绪稳定
     *   - 次日急于弥补亏损往往导致「报复性开仓」，雪上加霜
     *   - 半冷却期而非全冷却（不是完全禁止开仓），避免错过真正好机会
     */
    private static final double LARGE_LOSS_COOLDOWN_THRESHOLD = -0.03; // -3%
    /**
     * 半冷却期信号质量门槛：日亏>3%后次日仅允许评分>=80的高质量信号开仓
     */
    private static final int COOLDOWN_MIN_SIGNAL_STRENGTH = 80;
    /**
     * 记录大亏（日亏>3%）触发的日期，次日作为半冷却期日期判断依据。
     * null 表示未触发大亏冷却。
     */
    private volatile LocalDate largeLossCooldownTriggerDate = null;

    // ===== 板块止损保护 =====
    /**
     * 当日止损涉及的行业集合。
     * 当日某只股票触发止损后，其所属行业加入此黑名单，当日内禁止买入同行业其他股票。
     * 逻辑：止损往往是因为该板块出现系统性利空，同行业其他股票大概率也会受波及。
     * 每个交易日开盘前（refreshDailyBaseline 检测到新日期）自动清空。
     */
    private final Set<String> stopLossIndustriesToday = new java.util.HashSet<>();

    // ===== 实盘 Broker 适配器（可选，为 null 时为纯模拟模式）=====
    /**
     * QMT 实盘桥接适配器。
     * - 非 null：买卖信号触发时通过 QMT MiniQMT 实盘下单，成交后写回 Portfolio。
     * - null（默认）：纯模拟模式，行为与原有逻辑完全一致。
     * 通过 {@link #setLiveBrokerAdapter(LiveBrokerAdapter)} 注入，或在配置中开启 live_trade=true。
     */
    private volatile LiveBrokerAdapter liveBrokerAdapter = null;

    /**
     * 注入实盘适配器，切换为实盘交易模式。
     * 传入 null 可切换回纯模拟模式（无需重启）。
     */
    public void setLiveBrokerAdapter(LiveBrokerAdapter adapter) {
        this.liveBrokerAdapter = adapter;
        if (adapter != null) {
            log.info("[实盘模式] 已启用 QMT 实盘交易适配器，买卖信号将通过 QMT 实盘下单");
        } else {
            log.info("[模拟模式] 实盘适配器已移除，切换回纯模拟交易模式");
        }
    }

    /**
     * 是否处于实盘模式
     */
    public boolean isLiveMode() {
        return liveBrokerAdapter != null && liveBrokerAdapter.isLiveAvailable();
    }

    public AutoTrader(StockDataProvider dataProvider, double initialCapital,
                      int topN, int minScore, int scanIntervalMin) {
        this.dataProvider = dataProvider;
        this.initialCapital = initialCapital;
        this.topN = topN;
        this.minScore = minScore;
        this.scanIntervalMin = scanIntervalMin;
        this.scanIntervalSeconds = 0;
        this.strategyConfig = null; // 简单构造函数不使用配置对象，使用策略默认值
        this.dataDir = "./data";
        this.accountId = "auto_trader_main";
        this.username = "";
        this.reportDir = "./trade-reports";
        this.screener = new StockScreener(dataProvider);
        this.analyzer = new StockAnalyzer();
        this.feeCalculator = new FeeCalculator();
        this.strategy = new DayTradingStrategy();
        this.dayTradingScreener = null;
        this.persistence = new AccountPersistence(dataDir);
        // 初始化报告目录
        new File(this.reportDir).mkdirs();
        new File(this.reportDir + "/daily").mkdirs();
        new File(this.reportDir + "/trades").mkdirs();
        // 初始化微信推送服务
        this.pushService = WechatPushService.getInstance();
    }

    /**
     * 多用户模式构造函数
     * 每个用户有独立的数据目录、报告目录、策略配置和微信推送Key
     *
     * @param dataProvider    数据源
     * @param initialCapital  初始资金
     * @param topN            选股Top N
     * @param minScore        最低评分
     * @param scanIntervalMin 扫描间隔（分钟）
     * @param userId          用户ID（用于账户隔离）
     * @param accountDir      账户持久化目录
     * @param reportDir       报告保存目录
     * @param sc              策略配置
     * @param strategyType    策略类型
     * @param wechatOpenId    用户微信公众号 openid（公众号模板消息主通道，null则跳过）
     * @param wechatSendKey   用户 Server酱 SendKey（降级备用通道，null则不推送）
     */
    public AutoTrader(StockDataProvider dataProvider, double initialCapital,
                      int topN, int minScore, int scanIntervalMin,
                      String userId, String accountDir, String reportDir,
                      StrategyConfig sc, User.StrategyType strategyType,
                      String wechatOpenId, String wechatSendKey) {
        this(dataProvider, initialCapital, topN, minScore, scanIntervalMin,
                userId, null, accountDir, reportDir, sc, strategyType, wechatOpenId, wechatSendKey);
    }

    /** 兼容旧接口（仅 SendKey，无 openid） */
    public AutoTrader(StockDataProvider dataProvider, double initialCapital,
                      int topN, int minScore, int scanIntervalMin,
                      String userId, String accountDir, String reportDir,
                      StrategyConfig sc, User.StrategyType strategyType,
                      String wechatSendKey) {
        this(dataProvider, initialCapital, topN, minScore, scanIntervalMin,
                userId, null, accountDir, reportDir, sc, strategyType, null, wechatSendKey);
    }

    /**
     * 多用户模式构造函数（含用户名，用于微信推送账户识别）
     */
    public AutoTrader(StockDataProvider dataProvider, double initialCapital,
                      int topN, int minScore, int scanIntervalMin,
                      String userId, String username, String accountDir, String reportDir,
                      StrategyConfig sc, User.StrategyType strategyType,
                      String wechatOpenId, String wechatSendKey) {
        this.dataProvider = dataProvider;
        this.initialCapital = initialCapital;
        this.topN = topN;
        this.minScore = minScore;
        this.scanIntervalMin = scanIntervalMin;
        // [P2-3 优化] 保存策略配置引用，供运行时读取可配置参数（如追踪止盈回撤容忍度）
        this.strategyConfig = sc;
        // 从 StrategyConfig 中读取秒级扫描间隔（>0 时启用秒级调度）
        this.scanIntervalSeconds = (sc != null && sc.getScanIntervalSeconds() > 0)
                ? sc.getScanIntervalSeconds() : 0;
        this.dataDir = accountDir;
        this.accountId = (userId != null && !userId.isEmpty()) ? userId + "_auto_main" : "auto_trader_main";
        this.username = (username != null && !username.isEmpty()) ? username : (userId != null ? userId : "");
        this.reportDir = reportDir;
        this.screener = new StockScreener(dataProvider);
        this.analyzer = new StockAnalyzer();
        this.feeCalculator = new FeeCalculator();
        this.persistence = new AccountPersistence(accountDir);
        // 根据策略类型和配置创建策略实例
        // MEDIUM_LONG（中长期）和 SWAP_STRONG（换股/汰弱留强）均使用 MediumLongTermStrategy：
        //   两者核心逻辑相同（持有强势股+止盈止损），区别仅在 AutoTrader 的换股调度行为上
        if (strategyType == User.StrategyType.MEDIUM_LONG
                || strategyType == User.StrategyType.SWAP_STRONG) {
            this.strategy = sc != null ? new MediumLongTermStrategy(sc) : new MediumLongTermStrategy();
            this.dayTradingScreener = null;
        } else if (strategyType == User.StrategyType.DAY_TRADE
                && this.scanIntervalSeconds > 0) {
            // 小资金日内高频做T：启用 IntradayTradingStrategy + DayTradingStockScreener
            this.strategy = sc != null ? new IntradayTradingStrategy(sc) : new IntradayTradingStrategy();
            this.dayTradingScreener = new DayTradingStockScreener(dataProvider);
            log.info("[日内做T模式] 已启用 IntradayTradingStrategy + DayTradingStockScreener，扫描间隔={}秒",
                    this.scanIntervalSeconds);
        } else {
            // DAY_TRADE / AUCTION_LIMIT_UP / CUSTOM 均使用 DayTradingStrategy
            this.strategy = sc != null ? new DayTradingStrategy(sc) : new DayTradingStrategy();
            this.dayTradingScreener = null;
        }
        // 初始化报告目录
        new File(reportDir).mkdirs();
        new File(reportDir + "/daily").mkdirs();
        new File(reportDir + "/trades").mkdirs();
        // 用户专属微信推送（优先公众号模板消息 openid，降级 Server酱 SendKey）
        this.pushService = WechatPushService.forUser(wechatOpenId, wechatSendKey);
    }

    /**
     * 启动全自动交易（阻塞运行，直到程序退出）
     */
    public void start() {
        printHeader();

        // 1. 恢复或创建账户
        initPortfolio();
        // 加载完账户后立即设置当日基准（用持久化恢复的资产状态作为基准，与重启时间无关）
        refreshDailyBaseline();

        // 2. 初始化监控池：有持仓直接用持仓，无持仓才全市场选股
        if (!portfolio.getPositions().isEmpty()) {
            // 已有持仓，直接将持仓股票加入监控池，跳过耗时的全市场扫描
            watchlist = new ArrayList<>(portfolio.getPositions().keySet());
            log.info("检测到已有持仓 {} 只，直接使用持仓股票作为监控池，跳过全市场选股", watchlist.size());
            log.info("当前监控池：{}", watchlist);
        } else {
            // 无持仓，进行全市场选股
            if (dayTradingScreener != null && strategy instanceof IntradayTradingStrategy) {
                log.info("账户无持仓，正在进行日内做T专项选股扫描（全市场选出最优1支），请稍候...");
            } else {
                log.info("账户无持仓，正在进行全市场选股扫描，请稍候...");
            }
            refreshWatchlist();
            if (watchlist.isEmpty()) {
                // [Bug Fix] 原 fallback 硬编码贵州茅台(1500+元)，小资金账户根本买不起。
                // 改为按可用资金选择价格合适的保底标的：
                //   - 大资金(≥50万)：保留原默认池（贵州茅台/宁德时代/比亚迪）
                //   - 小资金(<50万)：使用低价蓝筹（中国石油、中国银行、农业银行、兖矿能源）
                watchlist = buildFallbackWatchlist();
            }
        }

        printWatchlist();

        // 3. 启动交易反馈回调服务器，并将回调地址注入推送服务（使推送消息中包含可点击链接）
        SystemConfig config = SystemConfig.getInstance();
        if (config.isCallbackServerEnabled()) {
            int cbPort = config.getCallbackServerPort();
            callbackServer = new TradeCallbackServer(cbPort, portfolio, persistence, pushService);
            callbackServer.start();
            // 自动获取本机局域网IP，生成回调基础URL并注入推送服务
            String localIp = getLocalIp();
            String callbackBaseUrl = String.format("http://%s:%d", localIp, cbPort);
            pushService.setCallbackBaseUrl(callbackBaseUrl);
            log.info("微信推送回调地址已配置: {}", callbackBaseUrl);
        }

        // 4. 发送系统启动通知
        pushService.sendSystemStart(initialCapital, topN, scanIntervalMin);

        // 5. 启动每分钟快速止损调度器（仅拉实时价，比全量扫描更轻量、更及时）
        SystemConfig sysConf = SystemConfig.getInstance();
        if (sysConf.isQuickStopLossEnabled()) {
            int qsInterval = sysConf.getQuickStopLossIntervalSeconds();
            quickStopScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "quick-stoploss");
                t.setDaemon(true);
                return t;
            });
            quickStopScheduler.scheduleAtFixedRate(this::quickStopLossCheck,
                    qsInterval, qsInterval, TimeUnit.SECONDS);
            log.info("快速止损检查已启动，间隔 {} 秒（仅拉实时价，不拉K线）", qsInterval);
        }

        // 6. 启动定时扫描
        log.info("启动定时监控，扫描间隔 {} 分钟...", scanIntervalMin);
        runScheduledLoop();
    }

    /**
     * 停止自动交易（多用户模式下由平台调用）
     */
    public void stopTrading() {
        stopRequested = true;
        if (schedulerRef != null) {
            schedulerRef.shutdownNow();
        }
        if (quickStopScheduler != null) {
            quickStopScheduler.shutdownNow();
        }
        if (callbackServer != null) {
            callbackServer.stop();
        }
        if (liveBrokerAdapter != null) {
            liveBrokerAdapter.shutdown();
            log.info("[实盘模式] 实盘适配器已关闭");
        }
        persistence.save(portfolio);
        printFinalReport();
        saveFinalSummaryReport();
        saveDailySummaryReport();
        sendDailySummaryPush();
        log.info("自动交易已停止");
    }

    /**
     * 动态调整扫描间隔（运行时生效，无需重启）
     * <p>
     * 将停止当前调度器，以新的间隔重新创建并立即触发一次扫描。
     *
     * @param newIntervalMin 新的扫描间隔（分钟），合法范围 1~120
     */
    public void adjustScanInterval(int newIntervalMin) {
        if (newIntervalMin < 1) newIntervalMin = 1;
        if (newIntervalMin > 120) newIntervalMin = 120;
        int old = this.scanIntervalMin;
        this.scanIntervalMin = newIntervalMin;
        log.info("[动态调整] 扫描间隔: {} 分钟 → {} 分钟，正在重建调度器...", old, newIntervalMin);

        // 停止旧调度器（不等待已提交任务完成，仅中断等待中的延迟）
        ScheduledExecutorService oldScheduler = this.schedulerRef;
        if (oldScheduler != null && !oldScheduler.isShutdown()) {
            oldScheduler.shutdownNow();
        }
        // 重新启动（不阻塞主线程，另起线程）
        new Thread(this::startScanScheduler, "scan-restart-" + newIntervalMin).start();
    }

    /**
     * 返回当前扫描间隔（分钟）
     */
    public int getScanIntervalMin() {
        return scanIntervalMin;
    }

    /**
     * 创建定时扫描调度器并立即执行第一次扫描（可被 adjustScanInterval 反复调用）
     * <p>
     * 若 scanIntervalSeconds > 0，使用秒级调度（适合日内做T高频策略）；
     * 否则使用 scanIntervalMin 分钟级调度（默认）。
     */
    private void startScanScheduler() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        this.schedulerRef = scheduler;

        // ===== 秒级调度（日内做T高频模式）=====
        if (scanIntervalSeconds > 0) {
            final int intervalSec = scanIntervalSeconds;
            log.info("[调度器] 启用秒级扫描模式，间隔 {} 秒（日内做T高频）", intervalSec);

            // 立即执行一次扫描
            scheduler.execute(this::runOneScan);

            // 之后每 N 秒执行一次
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (stopRequested) { scheduler.shutdownNow(); return; }
                    LocalTime now = LocalTime.now();
                    DayOfWeek dow = LocalDate.now().getDayOfWeek();

                    if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return;
                    if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) return;
                    if (now.isAfter(NOON_BREAK_START) && now.isBefore(NOON_BREAK_END)) return;

                    runOneScan();

                    // 收盘时保存日报 + 每日重新选股（只触发一次）
                    if (now.isAfter(LocalTime.of(14, 55)) && now.isBefore(LocalTime.of(15, 2))) {
                        if (!postMarketOptRunToday) {
                            postMarketOptRunToday = true;
                            log.info("[收盘] 保存当日汇总报告...");
                            saveDailySummaryReport();
                            sendDailySummaryPush();
                            // 异步选股（避免阻塞秒级扫描线程）
                            new Thread(() -> {
                                try { refreshWatchlist(); }
                                catch (Exception e) { log.warn("[盘后选股] 刷新选股池失败: {}", e.getMessage()); }
                            }, "post-market-screen").start();
                        }
                    }
                    // 次日重置盘后标记
                    if (now.isBefore(LocalTime.of(9, 0))) {
                        postMarketOptRunToday = false;
                    }
                } catch (Exception e) {
                    log.error("[秒级扫描] 异常: {}", e.getMessage(), e);
                }
            }, intervalSec, intervalSec, TimeUnit.SECONDS);

            log.info("[调度器] 秒级调度器已启动，扫描间隔 {} 秒", intervalSec);
            return;
        }

        // ===== 分钟级调度（默认模式）=====
        final int interval = this.scanIntervalMin; // 捕获当前值

        // 立即执行一次扫描
        scheduler.execute(this::runOneScan);

        // 之后按间隔周期执行
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (stopRequested) { scheduler.shutdownNow(); return; }
                LocalTime now = LocalTime.now();
                DayOfWeek dow = LocalDate.now().getDayOfWeek();

                // 周末不扫描
                if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                    log.info("[{}] 今天是周末，市场休市，跳过扫描", now.format(TIME_FMT));
                    return;
                }

                // 非交易时段不扫描
                if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) {
                    log.info("[{}] 当前非交易时段（{}~{}），等待开市",
                            now.format(TIME_FMT), MARKET_OPEN, MARKET_CLOSE);
                    return;
                }

                // 午休不扫描
                if (now.isAfter(NOON_BREAK_START) && now.isBefore(NOON_BREAK_END)) {
                    log.info("[{}] 午休时间（{}~{}），暂停扫描",
                            now.format(TIME_FMT), NOON_BREAK_START, NOON_BREAK_END);
                    return;
                }

                runOneScan();

                // 每天收盘后自动刷新选股池（下一交易日用新标的），同时保存当日汇总报告
                if (now.isAfter(LocalTime.of(14, 50)) && now.isBefore(LocalTime.of(15, 5))) {
                    log.info("[收盘] 保存当日汇总报告并刷新选股池...");
                    saveDailySummaryReport();
                    sendDailySummaryPush();
                    refreshWatchlist();
                    // 盘后参数优化（异步执行，不阻塞主扫描线程）
                    if (!postMarketOptRunToday) {
                        postMarketOptRunToday = true;
                        new Thread(() -> {
                            try {
                                runPostMarketOptimization();
                            } catch (Exception e) {
                                log.warn("[盘后优化] 参数优化出错: {}", e.getMessage());
                            }
                        }, "post-market-opt").start();
                    }
                }
                // 次日重置盘后优化标志
                if (now.isBefore(LocalTime.of(9, 0))) {
                    postMarketOptRunToday = false;
                }

            } catch (Exception e) {
                log.error("扫描异常: {}", e.getMessage(), e);
            }
        }, interval, interval, TimeUnit.MINUTES);

        log.info("[调度器] 已启动，扫描间隔 {} 分钟", interval);
    }

    /**
     * 主循环：按时间决定是否扫描，非交易时段等待
     */
    private void runScheduledLoop() {
        startScanScheduler();

        // 阻塞主线程
        log.info("====== 自动交易系统已启动，按 Ctrl+C 停止 ======");
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("程序退出，保存账户状态和最终报告...");
                persistence.save(portfolio);
                printFinalReport();
                saveFinalSummaryReport();
                saveDailySummaryReport();
                // 发送最终汇总推送
                sendDailySummaryPush();
                if (callbackServer != null) callbackServer.stop();
            }));
            Thread.currentThread().join(); // 阻塞
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 每分钟快速止损+清仓止盈检查（轻量级）
     * <p>
     * 仅拉取持仓股票的实时价格，不拉取历史K线，执行速度极快（每只股票约100ms）。
     * 两种情况直接触发，不等待下一次全量扫描（默认15分钟一次）：
     *   1. 止损：当前价格 ≤ 成本价 × (1 - stopLossPercent)
     *   2. 清仓止盈：当前价格 ≥ 成本价 × (1 + takeProfitFull)（防止拉升后回落错过最高点）
     * 减仓止盈（50%仓位）仍由全量扫描负责，不在此处处理。
     */
    private void quickStopLossCheck() {
        if (stopRequested) return;

        LocalTime now = LocalTime.now();
        DayOfWeek dow = LocalDate.now().getDayOfWeek();

        // 周末或非交易时段跳过
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return;
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) return;
        if (now.isAfter(NOON_BREAK_START) && now.isBefore(NOON_BREAK_END)) return;

        // 没有持仓，无需检查
        Map<String, Position> positions = portfolio.getPositions();
        if (positions.isEmpty()) return;

        // [P0-2 优化] 用 tradeLock 统一互斥，彻底杜绝与 runOneScan 的并发卖出竞态
        // tryLock(0)：若全量扫描正持有锁，立即放弃本次快速止损（全量扫描内已含止损逻辑，不遗漏）
        if (!tradeLock.tryLock()) {
            log.debug("[快速检查] 全量扫描正在执行，跳过本次快速止损（避免重复卖出）");
            return;
        }
        // 兼容保留旧的 AtomicBoolean 防重入标志
        if (quickStopRunning.getAndSet(true)) {
            tradeLock.unlock();
            return;
        }
        try {
            log.debug("[快速检查] 开始对 {} 只持仓做止损+清仓止盈检查...", positions.size());
            for (String code : new ArrayList<>(positions.keySet())) {
                if (stopRequested) break;
                try {
                    quickCheckOnePosition(code);
                } catch (Exception e) {
                    log.warn("[快速检查] 检查 {} 失败: {}", code, e.getMessage());
                }
            }
        } finally {
            quickStopRunning.set(false);
            tradeLock.unlock();
        }
    }

    /**
     * 快速检查单只持仓股票的止损线和清仓止盈线
     */
    private void quickCheckOnePosition(String code) {
        Position pos = portfolio.getPosition(code);
        if (pos == null || pos.getAvailableQuantity() <= 0) return;

        // 仅拉实时价（极轻量，约100ms）
        StockBar rt = dataProvider.getRealTimeQuote(code);
        if (rt == null || rt.getClose() <= 0) return;

        double currentPrice = rt.getClose();
        double avgCost = pos.getAvgCost();
        if (avgCost <= 0) return;

        // 更新持仓价格显示
        portfolio.updatePositionPrice(code, currentPrice);

        // 若持仓名称为空（历史遗留 bug），用实时行情补全
        if ((pos.getStockName() == null || pos.getStockName().isEmpty())
                && rt.getStockName() != null && !rt.getStockName().isEmpty()) {
            pos.setStockName(rt.getStockName());
            log.info("[名称修复] {} 持仓名称已从实时行情补全: {}", code, rt.getStockName());
        }

        double profitRate = (currentPrice - avgCost) / avgCost;
        double stopLossPct = getStrategyStopLossPercent();
        double takeProfitFullPct = getStrategyTakeProfitFullPercent();
        String stockName = getStockName(code);

        // ===== 1. 止损检查 =====
        // 【修复】分钟级快速止损与全量扫描策略层止损标准统一：
        // 策略层（generateSignal）已计算 ATR止损价 和 固定止损价 取较紧者，
        // 买入时将 ATR止损价写入 pos.atrStopPrice。
        // 此处同样取「ATR止损价」和「固定止损价」中的较高者（即较紧的止损）：
        //   - 有 ATR 止损价（新买入持仓）：两者取高，享受 ATR 动态收紧效果
        //   - 无 ATR 止损价（旧持仓/策略未提供）：降级为固定比例，兜底保障不变
        LocalTime now = LocalTime.now();
        LocalDate lastBuyDate = pos.getLastBuyDate();
        boolean isNextDayAfterBuy = lastBuyDate != null && lastBuyDate.isBefore(LocalDate.now());
        boolean isOpeningWindow = now.isAfter(LocalTime.of(9, 30)) && now.isBefore(LocalTime.of(10, 0));

        // ===== [P0-1 优化] 开盘止损5分钟延迟保护 =====
        // 背景：历史数据显示，9:30~9:35 开盘集合竞价后的前5分钟，股价波动极大。
        //   A股存在大量「开盘跳空低开随后快速回升」的情况（俗称「开盘杀跌」）。
        //   若次日开盘价触及止损线就立即止损，往往是错误时机，卖在了当日最低点。
        // 优化：次日 9:30~9:35 这5分钟内，暂停快速止损触发；
        //   9:35 之后才恢复正常止损逻辑（给5分钟让价格稳定后再判断趋势）。
        // 注意：此保护仅适用于「次日开盘窗口」，当日买入的止损不受影响。
        boolean isOpeningProtectWindow = isNextDayAfterBuy
                && now.isAfter(LocalTime.of(9, 29))  // 9:30开盘起
                && now.isBefore(LocalTime.of(9, 36)); // 9:36前（含9:35这一分钟）

        // ===== [P3-1 优化] 极端跳空低开立即止损（穿越保护窗口）=====
        // 背景：正常开盘保护窗口设计用于过滤「小幅低开随后回升」的场景；
        //   但当次日开盘价相较成本下跌 >= 5%（极端跳空），说明出现重大利空（一字跌停/重大事件），
        //   继续等待只会越套越深，必须立即止损出局，不能等到9:35保护期结束。
        // 规则：次日开盘保护期内（9:30~9:35），若开盘价 <= 成本 × (1 - EXTREME_GAP_DOWN_PCT)，
        //   立即触发极端跳空止损，穿越保护窗口限制。
        // 默认阈值：成本-5%（可通过常量配置）
        final double EXTREME_GAP_DOWN_PCT = 0.05; // 5%跳空立即止损
        if (isOpeningProtectWindow && profitRate <= -EXTREME_GAP_DOWN_PCT) {
            log.warn("[P3-1极端跳空止损] {} {} 次日开盘跳空低开={}%（<=-{}%），穿越保护窗口立即止损！成本={} 现价={}",
                    code, stockName,
                    String.format("%.2f", profitRate * 100),
                    String.format("%.0f", EXTREME_GAP_DOWN_PCT * 100),
                    String.format("%.2f", avgCost),
                    String.format("%.2f", currentPrice));
            TradeSignal gapStopSignal = TradeSignal.builder()
                    .signalId(java.util.UUID.randomUUID().toString())
                    .stockCode(code).stockName(stockName)
                    .signalType(TradeSignal.SignalType.STOP_LOSS)
                    .strength(100)
                    .suggestedPrice(currentPrice)
                    .strategyName("极端跳空止损")
                    .signalTime(LocalDateTime.now())
                    .reason(String.format("[P3-1极端跳空止损] 次日开盘跳空低开%.2f%%（超-5%%阈值），立即止损。成本=%.2f 现价=%.2f",
                            profitRate * 100, avgCost, currentPrice))
                    .build();
            executeSell(gapStopSignal);
            return;
        }

        if (isOpeningProtectWindow) {
            log.debug("[开盘保护] {} {} 次日开盘5分钟保护期（9:30-9:35），暂停止损判断（当前亏损={}%，成本={}，现价={}）",
                    code, stockName, String.format("%.2f", profitRate * 100),
                    String.format("%.2f", avgCost), String.format("%.2f", currentPrice));
            // 仅跳过止损，追踪止盈等其他检查继续执行
            // 注意：小幅低开（<5%）跳过——9:35后的止损也会触发
        } else {

        // 次日开盘紧止损（2%）：9:35后才执行，避免开盘瞬间假突破止损
        if (isNextDayAfterBuy && isOpeningWindow) {
            stopLossPct = 0.02;
        }
        double fixedStopPrice = avgCost * (1 - stopLossPct);

        // ATR 动态止损价（买入时策略层记录，0 表示未记录）
        double atrStopPrice = pos.getAtrStopPrice();
        double effectiveStopPrice;
        String stopLossTag;
        if (atrStopPrice > 0) {
            // 取 ATR止损价 和 固定止损价 中的较高者（即止损更紧的那个），与策略层保持一致
            effectiveStopPrice = Math.max(atrStopPrice, fixedStopPrice);
            stopLossTag = String.format("ATR+固定取较紧: ATR止损=%.2f 固定=%.2f 有效=%.2f（%s）",
                    atrStopPrice, fixedStopPrice, effectiveStopPrice,
                    isNextDayAfterBuy && isOpeningWindow ? "次日开盘紧止损" : String.format("固定%.0f%%", stopLossPct * 100));
        } else {
            // 旧持仓或策略未提供 ATR 止损价，降级为固定比例止损
            effectiveStopPrice = fixedStopPrice;
            stopLossTag = String.format("%s止损=%.2f",
                    isNextDayAfterBuy && isOpeningWindow ? "次日开盘紧" : "固定",
                    effectiveStopPrice);
        }

        // ===== [P2-1 优化] 移动止盈保本止损：盈利>5%后止损线上移到成本价 =====
        // 原理：当盈利已积累>5%时，不应再允许亏损止损出局。
        //   将止损线从「成本×(1-止损比例)」上移到「成本价」（保本线），
        //   这样即使股价回调，最多保本出局，已锁定>5%浮盈不再全部损失。
        // 分级逻辑：
        //   - 盈利 > 8%（高位）：止损线上移到成本×1.03（锁定3%利润，追踪更激进）
        //   - 盈利 5%~8%（中位）：止损线上移到成本×1.00（至少保本，不亏损）
        //   - 盈利 < 5%（普通）：保持原止损线（ATR/固定），正常止损逻辑
        // 豁免：次日开盘保护窗口内不启用（避免开盘跳空低开触发保本止损）
        if (!isOpeningProtectWindow) {
            double breakEvenStop = avgCost; // 保本线
            double lockProfitStop = avgCost * 1.03; // 锁利3%线
            if (profitRate > 0.08 && currentPrice > lockProfitStop) {
                // 高位盈利：止损线上移到锁利3%位
                if (effectiveStopPrice < lockProfitStop) {
                    effectiveStopPrice = lockProfitStop;
                    stopLossTag = String.format("高位锁利止损=%.2f（盈利=%.1f%%>8%%，止损上移到成本+3%%）",
                            effectiveStopPrice, profitRate * 100);
                    log.debug("[保本止损] {} 盈利={}%>8%，止损线上移到锁利位={}", code,
                            String.format("%.1f", profitRate * 100),
                            String.format("%.2f", effectiveStopPrice));
                }
            } else if (profitRate > 0.05 && currentPrice > breakEvenStop) {
                // 中位盈利：止损线上移到成本价（保本）
                if (effectiveStopPrice < breakEvenStop) {
                    effectiveStopPrice = breakEvenStop;
                    stopLossTag = String.format("保本止损=%.2f（盈利=%.1f%%>5%%，止损上移到成本价保本）",
                            effectiveStopPrice, profitRate * 100);
                    log.debug("[保本止损] {} 盈利={}%>5%，止损线上移到保本位={}", code,
                            String.format("%.1f", profitRate * 100),
                            String.format("%.2f", effectiveStopPrice));
                }
            }
        }

        if (currentPrice <= effectiveStopPrice) {
            log.warn("[快速止损触发] {} {} 成本={} 现价={} 止损价={} 亏损={}%，立即止损！（{}）",
                    code, stockName,
                    String.format("%.2f", avgCost),
                    String.format("%.2f", currentPrice),
                    String.format("%.2f", effectiveStopPrice),
                    String.format("%.2f", profitRate * 100),
                    stopLossTag);
            TradeSignal stopSignal = TradeSignal.builder()
                    .signalId(java.util.UUID.randomUUID().toString())
                    .stockCode(code).stockName(stockName)
                    .signalType(TradeSignal.SignalType.STOP_LOSS)
                    .strength(100)
                    .suggestedPrice(currentPrice)
                    .strategyName("快速止损")
                    .signalTime(LocalDateTime.now())
                    .reason(String.format("[分钟级快速止损] 成本=%.2f 现价=%.2f 止损价=%.2f 亏损=%.2f%%  %s",
                            avgCost, currentPrice, effectiveStopPrice, profitRate * 100, stopLossTag))
                    .build();
            executeSell(stopSignal);
            return;  // 止损后无需再检查止盈
        }

        } // end of isOpeningProtectWindow else block

        // ===== 2. 清仓止盈检查（防止拉升后快速回落错过最高点）=====
        // [P2-1 优化] 动态追踪清仓止盈：
        //   - 盈利未达减仓线（takeProfitHalf）前：使用固定清仓线，防止拉升后回落
        //   - 盈利已超过减仓线（takeProfitHalf）且有最高价记录时：
        //       改用动态追踪清仓线 = 最高价 × (1 - trailingStopPullback × 1.5)
        //       效果：强势股可以持续奔跑，只有从历史高点大幅回落时才清仓，不会被固定线过早锁止
        // 背景：历史数据显示若固定8%止盈，强势股往往涨到7.x%后震荡，被清仓后继续涨，损失了大行情
        double takeProfitHalfPct = getStrategyTakeProfitHalfPercent();
        double highestPrice = pos.getHighestPrice();
        double trailingStopPullback = strategyConfig != null
                ? strategyConfig.getEffectiveTrailingStopPullback()
                : 0.03;

        boolean inTrailingMode = highestPrice > 0
                && highestPrice > avgCost * (1 + takeProfitHalfPct); // 已经从高点进入追踪模式

        if (inTrailingMode) {
            // 追踪模式：清仓线 = 最高价 × (1 - trailingStopPullback × 2)
            // 使用2倍容忍度：追踪清仓需要比追踪减仓（1×）更大的空间，避免因为小波动提前清仓
            double trailingClearLine = highestPrice * (1 - trailingStopPullback * 2);
            if (currentPrice <= trailingClearLine) {
                log.info("[P2-1 追踪清仓止盈] {} {} 最高价={} 现价={} 追踪清仓线={} 浮盈={}%，强势回撤超过{}%×2，清仓锁利",
                        code, stockName,
                        String.format("%.2f", highestPrice),
                        String.format("%.2f", currentPrice),
                        String.format("%.2f", trailingClearLine),
                        String.format("%.2f", profitRate * 100),
                        String.format("%.0f", trailingStopPullback * 100));
                TradeSignal takeProfitSignal = TradeSignal.builder()
                        .signalId(java.util.UUID.randomUUID().toString())
                        .stockCode(code).stockName(stockName)
                        .signalType(TradeSignal.SignalType.TAKE_PROFIT)
                        .strength(93)
                        .suggestedPrice(currentPrice)
                        .strategyName("追踪清仓止盈")
                        .signalTime(LocalDateTime.now())
                        .reason(String.format("[P2-1追踪清仓止盈] 最高价=%.2f 现价=%.2f 清仓线=%.2f 浮盈=%.2f%% 清仓",
                                highestPrice, currentPrice, trailingClearLine, profitRate * 100))
                        .build();
                executeSell(takeProfitSignal);
                return;
            }
        } else {
            // 常规模式：使用固定清仓线（盈利未超过减仓线时，沿用原逻辑保底）
            if (profitRate >= takeProfitFullPct) {
                log.info("[快速清仓止盈] {} {} 成本={} 现价={} 盈利={}%，立即清仓！",
                        code, stockName,
                        String.format("%.2f", avgCost),
                        String.format("%.2f", currentPrice),
                        String.format("%.2f", profitRate * 100));
                TradeSignal takeProfitSignal = TradeSignal.builder()
                        .signalId(java.util.UUID.randomUUID().toString())
                        .stockCode(code).stockName(stockName)
                        .signalType(TradeSignal.SignalType.TAKE_PROFIT)
                        .strength(95)
                        .suggestedPrice(currentPrice)
                        .strategyName("快速清仓止盈")
                        .signalTime(LocalDateTime.now())
                        .reason(String.format("[分钟级清仓止盈] 成本=%.2f 现价=%.2f 盈利=%.2f%%（止盈线%.0f%%） 清仓",
                                avgCost, currentPrice, profitRate * 100, takeProfitFullPct * 100))
                        .build();
                executeSell(takeProfitSignal);
                return;
            }
        }

        // ===== 3. 追踪止盈检查（Trailing Stop，减仓50%）=====
        // 触发条件：
        //   a. 当前盈利 >= takeProfitHalfPct（已盈利达到减仓目标，说明股票有不错浮盈）
        //   b. 从持仓期间最高价回落幅度 >= 追踪止盈容忍幅度
        // 效果：让利润奔跑，但在明显回撤时及时锁利（卖出50%）
        if (highestPrice > 0 && profitRate >= takeProfitHalfPct) {
            double pullbackRate = (highestPrice - currentPrice) / highestPrice;
            // [P2-3 优化] 追踪止盈回撤容忍度从StrategyConfig读取，支持按策略差异化配置
            // 默认3%；中长期策略4~5%；短线做T 2%；未配置时降级为默认3%
            if (pullbackRate >= trailingStopPullback) {
                log.info("[追踪止盈] {} {} 最高价={} 现价={} 回撤={}%，从高点回落超过{}%（配置值），卖出50%%锁利",
                        code, stockName,
                        String.format("%.2f", highestPrice),
                        String.format("%.2f", currentPrice),
                        String.format("%.2f", pullbackRate * 100),
                        String.format("%.0f", trailingStopPullback * 100));
                TradeSignal trailingSignal = TradeSignal.builder()
                        .signalId(java.util.UUID.randomUUID().toString())
                        .stockCode(code).stockName(stockName)
                        .signalType(TradeSignal.SignalType.TAKE_PROFIT)
                        .strength(88)
                        .suggestedPrice(currentPrice)
                        .strategyName("追踪止盈")
                        .signalTime(LocalDateTime.now())
                        .reason(String.format("[追踪止盈] 最高价=%.2f 现价=%.2f 回撤=%.2f%% 浮盈=%.2f%% 容忍=%.0f%% 卖出50%%",
                                highestPrice, currentPrice, pullbackRate * 100, profitRate * 100,
                                trailingStopPullback * 100))
                        .build();
                executeSell(trailingSignal);
            }
        }
    }

    /**
     * 获取当前策略的减仓止盈比例（从策略实例中读取，默认5%）
     */
    private double getStrategyTakeProfitHalfPercent() {
        if (strategy instanceof DayTradingStrategy) {
            return ((DayTradingStrategy) strategy).getTakeProfitHalfPercent();
        }
        if (strategy instanceof MediumLongTermStrategy) {
            return ((MediumLongTermStrategy) strategy).getTakeProfitHalfPercent();
        }
        return 0.05;
    }

    /**
     * 获取当前策略的止损比例（从策略实例中读取，默认7%）
     */
    private double getStrategyStopLossPercent() {
        if (strategy instanceof DayTradingStrategy) {
            return ((DayTradingStrategy) strategy).getStopLossPercent();
        }
        if (strategy instanceof MediumLongTermStrategy) {
            return ((MediumLongTermStrategy) strategy).getStopLossPercent();
        }
        return 0.07;
    }

    /**
     * 获取当前策略的清仓止盈比例（从策略实例中读取，默认8%）
     */
    private double getStrategyTakeProfitFullPercent() {
        if (strategy instanceof DayTradingStrategy) {
            return ((DayTradingStrategy) strategy).getTakeProfitFullPercent();
        }
        if (strategy instanceof MediumLongTermStrategy) {
            return ((MediumLongTermStrategy) strategy).getTakeProfitFullPercent();
        }
        return 0.08;
    }

    /**
     * 刷新当日基准资产快照（跨日时自动重置）
     * 可在任意时刻调用：runOneScan 开头、sendDailySummaryPush 前均会调用，
     * 保证即使盘后重启也能正确记录当日开始基准。
     */
    private void refreshDailyBaseline() {
        LocalDate today = LocalDate.now();
        if (dailyStartDate == null || !dailyStartDate.equals(today)) {
            dailyStartDate = today;
            dailyStartAssets = portfolio.getTotalAssets();
            log.info("[每日基准] {} 开始，基准总资产 = {}", today, String.format("%.2f", dailyStartAssets));
            // ===== 每日重置板块止损黑名单 =====
            if (!stopLossIndustriesToday.isEmpty()) {
                log.info("[板块保护] 新交易日开始，清空止损行业黑名单: {}", stopLossIndustriesToday);
                stopLossIndustriesToday.clear();
            }
            // ===== 每日重置止损冷却时间 =====
            // 止损冷却跨日后自动解除（新的一天应正常开仓，不让昨日止损阻碍今日交易）
            if (lastStopLossTimeMs > 0) {
                lastStopLossTimeMs = 0L;
                log.debug("[止损冷却] 新交易日开始，止损冷却已重置");
            }
            // ===== 每日重置候选股当日去重标记（允许新的一天重新计入达标天数）=====
            // 不清空 candidateQualifyDays 本身（保留跨日的累计达标天数）
            // 只清空 seen 集合，使今日的扫描记录可以被重新写入
            if (!candidateQualifyDaySeen.isEmpty()) {
                candidateQualifyDaySeen.clear();
                log.debug("[换仓验证] 新交易日开始，候选去重标记已清空");
            }
            // ===== 每日重置盘后优化标记 =====
            postMarketOptRunToday = false;
            // ===== 每日重置账户级日止损熔断状态 =====
            if (dailyCircuitBroken) {
                dailyCircuitBroken = false;
                log.info("[熔断重置] 新交易日开始，账户级日止损熔断已解除，恢复正常开仓");
            }
        }
    }

    /**
     * 获取当日盈亏（元）：当前总资产 - 当日开始时的基准资产
     */
    public double getDailyProfit() {
        if (dailyStartAssets < 0) return 0;
        return portfolio.getTotalAssets() - dailyStartAssets;
    }

    /**
     * 盘后自动参数优化（Walk-Forward 滚动优化）
     * <p>
     * 每个交易日收盘后（15:00~15:30）自动触发，对当前持仓的每只股票执行 Walk-Forward 参数优化：
     * - 用近30天（IS=20天 + OOS=10天）数据滚动搜索最优止损/止盈比例
     * - 将最优参数建议打印到日志，运维人员可据此手动调整策略配置
     * - 注意：当前不自动修改在运行策略参数（不可变设计），仅作为辅助决策工具
     * <p>
     * 参数搜索空间：
     *   stopLoss:   3% / 5% / 7%
     *   takeProfit: 6% / 8% / 10% / 12%
     */
    private void runPostMarketOptimization() {
        log.info("[盘后优化] 开始对持仓股票执行 Walk-Forward 参数优化（IS=20天 OOS=10天）...");
        List<String> holdingCodes = new ArrayList<>(portfolio.getPositions().keySet());
        if (holdingCodes.isEmpty()) {
            log.info("[盘后优化] 当前无持仓，跳过参数优化");
            return;
        }

        StrategyOptimizer optimizer = new StrategyOptimizer(2); // 限制2线程，避免盘后大量占用CPU
        Map<String, double[]> paramGrid = new java.util.LinkedHashMap<>();
        paramGrid.put("stopLoss",   new double[]{0.03, 0.05, 0.07});
        paramGrid.put("takeProfit", new double[]{0.06, 0.08, 0.10, 0.12});

        boolean isMediumLong = strategy instanceof MediumLongTermStrategy;
        LocalDate endDate   = LocalDate.now();
        LocalDate startDate = endDate.minusDays(60); // 取最近60天K线（IS=20+OOS=10，滚动约2~3次）

        for (String code : holdingCodes) {
            try {
                String stockName = getStockName(code);
                List<StockBar> bars = dataProvider.getDailyBars(code, startDate, endDate,
                        StockBar.AdjustType.FORWARD);
                if (bars == null || bars.size() < 30) {
                    log.debug("[盘后优化] {} K线不足30天，跳过", code);
                    continue;
                }
                java.util.function.Function<Map<String, Double>, com.stocktrader.strategy.TradingStrategy> factory;
                if (isMediumLong) {
                    // 使用策略已有的止损/止盈配置作为基线；仓位/持仓/评分参数固定（回测用途，不影响实盘）
                    factory = params -> new MediumLongTermStrategy(
                            params.get("stopLoss"), params.get("takeProfit") * 0.5, params.get("takeProfit"),
                            0.5,  // maxPositionRatio 默认50%
                            5,    // maxPositions 默认5只
                            3,    // minHoldDays 默认3天
                            60);  // minScore 默认60分
                } else {
                    factory = params -> new DayTradingStrategy(
                            params.get("stopLoss"), params.get("takeProfit") * 0.5, params.get("takeProfit"),
                            0.0,  // doTProfit 默认0
                            0.5); // maxPositionRatio 默认50%
                }

                StrategyOptimizer.WalkForwardResult wfResult = optimizer.walkForward(
                        code, stockName, bars,
                        startDate, endDate,
                        20, 10,
                        paramGrid, factory,
                        StrategyOptimizer.OptimizeTarget.SHARPE,
                        BacktestEngine.SlippageConfig.defaultConfig(),
                        portfolio.getTotalAssets());

                // 汇总最优参数（取OOS窗口中出现最多的参数组合）
                if (!wfResult.windows.isEmpty()) {
                    Map<String, Double> bestParams = wfResult.windows.get(wfResult.windows.size() - 1).bestParams;
                    log.info("[盘后优化] {} {} | 最新窗口最优参数：stopLoss={}% takeProfit={}% | OOS累计收益={}% IS/OOS衰减={}",
                            code, stockName,
                            String.format("%.1f", bestParams.getOrDefault("stopLoss", 0.07) * 100),
                            String.format("%.1f", bestParams.getOrDefault("takeProfit", 0.08) * 100),
                            String.format("%.2f", wfResult.combinedOosReturn),
                            String.format("%.3f", wfResult.isOosDecayRatio));
                }
            } catch (Exception e) {
                log.warn("[盘后优化] {} 参数优化失败: {}", code, e.getMessage());
            }
        }
        log.info("[盘后优化] Walk-Forward 参数优化完成，详情见上方日志");
    }

    /**
     * 大盘择时判断：当大盘处于空头环境时返回 true，触发暂停新开仓保护。
     * <p>
     * 触发条件：
     * 1. 当日大盘跌幅 >= 1.5%（今天大跌，系统性风险高）—— 单独触发，无需交叉验证
     * 2. 上证指数 MA5 < MA20 【且】 沪深300 MA5 < MA20（双重确认空头排列）
     *    [P2-2 优化] 原逻辑仅用上证指数，单指数误触发率高（上证偏弱但沪深300仍强时会错误封禁）。
     *    新增沪深300交叉验证：两个指数均满足MA5<MA20才触发弱势拦截，降低单指数噪音误触发。
     * <p>
     * 结果缓存5分钟，避免每次买入都重新拉行情造成延迟。
     */
    private boolean isMarketBearishNow() {
        long now = System.currentTimeMillis();
        // 缓存未过期时直接返回缓存结果
        if (now - marketStatusLastUpdate < MARKET_STATUS_CACHE_MS) {
            return marketBearish;
        }
        try {
            // 拉取上证指数实时行情
            StockBar indexRt = dataProvider.getRealTimeQuote(MARKET_INDEX_CODE);
            if (indexRt == null || indexRt.getClose() <= 0) {
                marketStatusLastUpdate = now;
                return false; // 数据异常时不阻断交易
            }
            // 判断条件1：当日跌幅超过阈值（changePercent 单位为%，如-1.5表示跌1.5%）
            double changePct = indexRt.getChangePercent();
            boolean todayBigDrop = changePct <= MARKET_DROP_THRESHOLD * 100;

            // 判断条件2：[P2-2] 上证指数 MA5<MA20 且 沪深300 MA5<MA20（双指数交叉验证）
            // 仅当两个指数均为空头排列时才触发，降低单指数噪音导致的误触发率
            boolean ma5BelowMa20 = false;
            // [P0-2] 提升到 try 块外，供后续 marketDeepBearish 赋值使用
            boolean sh000001DeepBearish = false; // MA5<MA20<MA60 三线空头
            // [P0-2] indexBars 声明提升到 try 块外，供 updateMarketSentimentScore() 复用
            List<StockBar> indexBars = null;
            try {
                LocalDate endDate = LocalDate.now();
                LocalDate startDate = endDate.minusMonths(3);
                // 上证指数 MA 判断（含 MA60 深度熊市检测）
                boolean sh000001Bearish = false;
                indexBars = dataProvider.getDailyBars(
                        MARKET_INDEX_CODE, startDate, endDate, StockBar.AdjustType.NONE);
                if (indexBars != null && indexBars.size() >= 20) {
                    List<Double> closes = new ArrayList<>();
                    for (StockBar b : indexBars) closes.add(b.getClose());
                    double ma5  = com.stocktrader.analysis.TechnicalIndicator.sma(closes, 5);
                    double ma20 = com.stocktrader.analysis.TechnicalIndicator.sma(closes, 20);
                    if (ma5 > 0 && ma20 > 0) {
                        sh000001Bearish = ma5 < ma20;
                        // [P0-2] 计算 MA60：三线空头排列检测
                        if (sh000001Bearish && closes.size() >= 60) {
                            double ma60 = com.stocktrader.analysis.TechnicalIndicator.sma(closes, 60);
                            if (ma60 > 0) {
                                sh000001DeepBearish = ma20 < ma60; // MA5<MA20 已满足，再加 MA20<MA60
                            }
                        }
                    }
                }
                // [P2-2] 沪深300交叉验证：仅当上证也弱时才请求沪深300，减少不必要请求
                if (sh000001Bearish) {
                    boolean csi300Bearish = false;
                    try {
                        List<StockBar> csi300Bars = dataProvider.getDailyBars(
                                CSI300_INDEX_CODE, startDate, endDate, StockBar.AdjustType.NONE);
                        if (csi300Bars != null && csi300Bars.size() >= 20) {
                            List<Double> c300 = new ArrayList<>();
                            for (StockBar b : csi300Bars) c300.add(b.getClose());
                            double ma5c  = com.stocktrader.analysis.TechnicalIndicator.sma(c300, 5);
                            double ma20c = com.stocktrader.analysis.TechnicalIndicator.sma(c300, 20);
                            if (ma5c > 0 && ma20c > 0) {
                                csi300Bearish = ma5c < ma20c;
                            }
                        }
                    } catch (Exception e) {
                        // 沪深300数据获取失败时降级：沿用上证单指数判断（保守处理）
                        log.debug("[大盘择时] 沪深300K线获取失败，降级为上证单指数判断: {}", e.getMessage());
                        csi300Bearish = true; // 保守处理：数据不可用时假定沪深300也弱
                    }
                    // 双指数均弱才触发弱势拦截
                    ma5BelowMa20 = csi300Bearish;
                    if (sh000001Bearish && !csi300Bearish) {
                        log.info("[大盘择时] 上证MA5<MA20但沪深300MA5>=MA20，两指数分化，不触发弱势拦截（降低误触发）");
                    }
                }
            } catch (Exception e) {
                log.debug("[大盘择时] 获取指数K线失败，跳过MA判断: {}", e.getMessage());
            }

            cachedIndexChangePct = changePct;
            marketBearish = todayBigDrop || ma5BelowMa20;
            // [P0-2] 深度熊市：MA5<MA20<MA60（三线空头排列），同时禁止换仓
            // 条件：必须满足 ma5BelowMa20（双指数已验证弱势）且上证 MA20<MA60
            marketDeepBearish = ma5BelowMa20 && sh000001DeepBearish;
            marketStatusLastUpdate = now;

            // [P0-2] 更新大盘情绪综合评分（涨跌家数比 + 指数量能）
            updateMarketSentimentScore(indexBars);

            if (marketDeepBearish) {
                log.warn("[大盘择时-深度熊市] MA5<MA20<MA60 三线空头排列！当日涨跌幅={}%，暂停新开仓+禁止换仓（情绪分={}）",
                        String.format("%.2f", changePct), marketSentimentScore);
            } else if (marketBearish) {
                log.info("[大盘择时] 大盘风险预警：当日涨跌幅={} %，双指数MA5{}MA20，暂停新开仓（情绪分={}）",
                        String.format("%.2f", changePct), ma5BelowMa20 ? "<" : ">=", marketSentimentScore);
            } else {
                log.debug("[大盘情绪] 当日涨跌幅={}%，情绪综合评分={}", String.format("%.2f", changePct), marketSentimentScore);
            }
        } catch (Exception e) {
            log.debug("[大盘择时] 获取大盘行情异常，跳过择时过滤: {}", e.getMessage());
            marketStatusLastUpdate = now;
        }
        return marketBearish;
    }

    /**
     * [P0-2] 更新大盘情绪综合评分（0~100）
     * <p>
     * 评分维度：
     * 1. 涨跌家数比（权重60%）：
     *    通过东方财富行情接口统计全市场涨跌家数，
     *    涨跌比 = 上涨家数 / (上涨家数 + 下跌家数)，映射到 0~100 分。
     *    - 涨跌比 >= 0.65：市场普涨，得满分100
     *    - 涨跌比 = 0.50：五五开，得50分
     *    - 涨跌比 <= 0.30：普跌，得0分
     * 2. 指数量能（权重40%）：
     *    当日成交量相对5日均量的比值，衡量市场活跃度。
     *    - 量比 >= 1.5：放量，得100分
     *    - 量比 = 1.0：平量，得50分
     *    - 量比 <= 0.5：缩量，得0分
     * <p>
     * 综合评分 = 涨跌比得分 * 0.60 + 量能比得分 * 0.40
     * 评分 < 35：情绪悲观，动态提高买入信号阈值（85→90），更严格过滤
     * </p>
     *
     * @param indexBars 上证指数近期日K线（已在 isMarketBearishNow() 中获取，避免重复请求）
     */
    private void updateMarketSentimentScore(List<StockBar> indexBars) {
        try {
            // ===== 维度1：涨跌家数比（权重60%）=====
            // 通过东方财富全市场行情接口获取涨跌统计
            int upCount = 0, downCount = 0;
            try {
                // 东方财富全量行情列表（只拉 f3=涨跌幅 字段，快速统计涨跌家数）
                String breadthUrl = "https://push2.eastmoney.com/api/qt/clist/get?" +
                        "pn=1&pz=5000&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281&fltt=2&invt=2" +
                        "&fid=f3&fs=m:0+t:6,m:0+t:13,m:0+t:80,m:1+t:2,m:1+t:23" +
                        "&fields=f3&_=" + System.currentTimeMillis();
                // 通过反射调用 dataProvider 内部的 httpGet（TongHuaShunDataProvider）
                // 由于接口未暴露 httpGet，此处使用 OkHttp 独立调用
                okhttp3.OkHttpClient breadthClient = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .build();
                okhttp3.Request req = new okhttp3.Request.Builder().url(breadthUrl).build();
                try (okhttp3.Response resp = breadthClient.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        String body = resp.body().string();
                        com.fasterxml.jackson.databind.ObjectMapper mapper =
                                new com.fasterxml.jackson.databind.ObjectMapper();
                        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
                        com.fasterxml.jackson.databind.JsonNode items = root.path("data").path("diff");
                        if (items.isArray()) {
                            for (com.fasterxml.jackson.databind.JsonNode item : items) {
                                double chgPct = item.path("f3").asDouble(0);
                                if (chgPct > 0) upCount++;
                                else if (chgPct < 0) downCount++;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[P0-2情绪] 获取涨跌家数失败: {}", e.getMessage());
            }

            // 涨跌比评分（0~100）
            int breadthScore = 50; // 默认中性
            int total = upCount + downCount;
            if (total >= 100) { // 至少100只股票才有统计意义
                double upRatio = (double) upCount / total;
                // 线性映射：[0.30, 0.65] → [0, 100]
                breadthScore = (int) Math.round(Math.min(100, Math.max(0,
                        (upRatio - 0.30) / (0.65 - 0.30) * 100)));
            }

            // ===== 维度2：指数量能（权重40%）=====
            // 用已有的 indexBars（上证指数日K线），计算当日量比（当日量/5日均量）
            int volumeScore = 50; // 默认平量
            if (indexBars != null && indexBars.size() >= 6) {
                int sz = indexBars.size();
                long todayVol = indexBars.get(sz - 1).getVolume();
                // 计算前5日平均量（不含今日）
                long sum5 = 0;
                for (int i = sz - 6; i < sz - 1; i++) {
                    sum5 += indexBars.get(i).getVolume();
                }
                double avg5Vol = sum5 / 5.0;
                if (avg5Vol > 0) {
                    double volRatio = todayVol / avg5Vol;
                    // 线性映射：[0.5, 1.5] → [0, 100]
                    volumeScore = (int) Math.round(Math.min(100, Math.max(0,
                            (volRatio - 0.5) / (1.5 - 0.5) * 100)));
                }
            }

            // ===== 综合评分（涨跌比 60% + 量能 40%）=====
            int newScore = (int) Math.round(breadthScore * 0.60 + volumeScore * 0.40);
            int oldScore = marketSentimentScore;
            // 平滑处理：新值与旧值各取一半，避免单次数据抖动引发评分剧烈变化
            marketSentimentScore = (int) Math.round(newScore * 0.7 + oldScore * 0.3);

            log.info("[P0-2情绪评分] 上涨={}家 下跌={}家 涨跌比分={} 量能分={} → 综合情绪分={}（上次={}）",
                    upCount, downCount, breadthScore, volumeScore, marketSentimentScore, oldScore);

        } catch (Exception e) {
            log.debug("[P0-2情绪] 更新情绪评分异常，保留上次评分({}): {}", marketSentimentScore, e.getMessage());
        }
    }

    /**
     * 执行一次扫描（核心逻辑）
     */
    public void runOneScan() {
        // ===== 每日基准快照：无论交易时段与否，优先刷新（确保当日盈亏计算有基准）=====
        refreshDailyBaseline();

        // ===== 非交易时段保护：无论何时被调用（立即执行或定时触发），都先检查时间 =====
        LocalTime nowCheck = LocalTime.now();
        DayOfWeek dowCheck = LocalDate.now().getDayOfWeek();
        if (dowCheck == DayOfWeek.SATURDAY || dowCheck == DayOfWeek.SUNDAY) {
            log.info("[{}] 今天是周末，市场休市，跳过扫描", nowCheck.format(TIME_FMT));
            return;
        }
        if (nowCheck.isBefore(MARKET_OPEN) || nowCheck.isAfter(MARKET_CLOSE)) {
            log.info("[{}] 当前非交易时段（{}~{}），跳过扫描",
                    nowCheck.format(TIME_FMT), MARKET_OPEN, MARKET_CLOSE);
            return;
        }
        if (nowCheck.isAfter(NOON_BREAK_START) && nowCheck.isBefore(NOON_BREAK_END)) {
            log.info("[{}] 午休时间（{}~{}），跳过扫描",
                    nowCheck.format(TIME_FMT), NOON_BREAK_START, NOON_BREAK_END);
            return;
        }
        // ===== 时间检查通过，尝试获取交易锁，执行扫描 =====
        // [P0-2 优化] 给正在执行的快速止损最多100ms完成，再持锁扫描；若仍拿不到则等待（fair锁不超时）
        boolean lockAcquired = false;
        try {
            lockAcquired = tradeLock.tryLock(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[runOneScan] 获取交易锁时被中断，跳过本轮扫描");
            return;
        }
        if (!lockAcquired) {
            // 100ms仍未获取到锁，说明快速止损持续时间较长，直接等待获取（fair锁保证最终能拿到）
            log.debug("[runOneScan] 快速止损持锁中，等待获取tradeLock...");
            tradeLock.lock();
        }
        try {

        // ===== 实盘持仓同步（每分钟从 QMT 刷新持仓和资金，保持模拟账户与实盘一致）=====
        if (liveBrokerAdapter != null) {
            liveBrokerAdapter.syncFromLiveIfDue(portfolio);
        }

        scanRoundCount++;

        if (watchlist.isEmpty()) {
            // 监控池为空，直接触发全市场选股
            log.info("监控池为空，自动触发全市场选股...");
            refreshWatchlist();
            if (watchlist.isEmpty()) {
                watchlist = buildFallbackWatchlist();
            }
        }

        // ===== 满仓时定频大盘扫描，补充置换候选 =====
        // 当已满仓（持仓==topN）时，每 FULL_SCAN_EVERY_N_ROUNDS 轮执行一次全市场扫描：
        //   - 发现评分更高的候选股加入监控池（非持仓）
        //   - 下一步 scanStock 扫描这些候选股，若触发 STRONG_BUY 则进入 blockedStrongBuySignals
        //   - 最终由 tryPositionSwap 决定是否置换
        boolean isFull = portfolio.getPositions().size() >= topN;
        if (isFull && scanRoundCount % FULL_SCAN_EVERY_N_ROUNDS == 0) {
            log.info("[定频大盘扫描] 已满仓({}/{}), 第{}轮，触发全市场选股以寻找置换候选...",
                    portfolio.getPositions().size(), topN, scanRoundCount);
            refreshWatchlistForSwap();
        }

        log.info("[{}] 开始扫描 {} 只监控股票...",
                LocalDateTime.now().format(DT_FMT), watchlist.size());

        // T+1 解锁：将昨日及更早买入的持仓设为可用
        portfolio.unlockT1Positions();

        // 清空上一轮被阻塞的强烈买入信号
        blockedStrongBuySignals.clear();

        for (String code : new ArrayList<>(watchlist)) {
            try {
                scanStock(code);
            } catch (Exception e) {
                log.error("扫描股票 {} 失败: {}", code, e.getMessage());
            }
        }

        // 扫描结束后：尝试仓位置换（用强烈买入新股替换最弱持仓）
        tryPositionSwap();

        // 打印账户状态摘要
        printAccountSummary();

        // 扫描完成后检查：持仓不足 topN 且有足够资金，自动补充选股
        int currentPositions = portfolio.getPositions().size();
        double availableCash = portfolio.getAvailableCash();
        // 单只股票最小建仓资金（按最低价估算：至少能买100股 * 5元）
        double minBuildCost = 500.0;
        if (currentPositions < topN && availableCash > minBuildCost) {
            // 监控池中非持仓的股票数量不足时，补充选股
            long nonHoldingWatch = watchlist.stream()
                    .filter(c -> !portfolio.getPositions().containsKey(c))
                    .count();
            if (nonHoldingWatch == 0) {
                log.info("持仓({})不足目标({})，可用资金{}元，自动补充选股...",
                        currentPositions, topN, (int) availableCash);
                refreshWatchlist();
            }
        }

        // ===== 更新最大回撤（每次扫描结束时刷新峰值和回撤记录）=====
        double currentTotalAssets = portfolio.getTotalAssets();
        if (peakTotalAssets < 0) {
            peakTotalAssets = currentTotalAssets; // 首次初始化
        } else if (currentTotalAssets > peakTotalAssets) {
            peakTotalAssets = currentTotalAssets; // 新高，更新峰值
        } else {
            double drawdownAbs = peakTotalAssets - currentTotalAssets;
            if (drawdownAbs > maxDrawdownAbs) {
                maxDrawdownAbs = drawdownAbs;
                maxDrawdownPct = peakTotalAssets > 0 ? drawdownAbs / peakTotalAssets * 100 : 0;
            }
        }

        // 每次扫描后保存账户状态
        persistence.save(portfolio);
        } finally {
            // [P0-2] 释放交易锁
            tradeLock.unlock();
        }
    }

    /**
     * 扫描单只股票，生成信号并执行
     */
    private void scanStock(String code) {
        // [P1-2 优化] 优先复用本轮 screenResults 中已缓存的K线和分析结果，避免重复HTTP请求
        List<StockBar> bars = null;
        AnalysisResult cachedAnalysis = null;
        for (StockScreener.ScreenResult sr : screenResults) {
            if (sr.stockCode.equals(code) && sr.bars != null && sr.bars.size() >= 60) {
                bars = new ArrayList<>(sr.bars); // 防御性拷贝，避免后续修改影响缓存
                cachedAnalysis = sr.analysis;
                log.debug("[K线复用] {} 命中本轮screenResults缓存（{}条），跳过远程请求", code, bars.size());
                break;
            }
        }

        // 未命中缓存时，重新拉取（第一轮、换仓候选等场景）
        if (bars == null) {
            // [P1-1 优化] K线范围从1年缩短为180天日历（含假日缓冲），实际取得约120+个交易日数据
            LocalDate endDate = LocalDate.now();
            LocalDate startDate = endDate.minusDays(180);
            bars = dataProvider.getDailyBars(code, startDate, endDate, StockBar.AdjustType.FORWARD);
        }

        if (bars == null || bars.size() < 60) {
            log.debug("股票 {} 数据不足，跳过", code);
            return;
        }

        // 追加实时行情（实时价优先，确保信号使用最新价格）
        StockBar realtime = dataProvider.getRealTimeQuote(code);
        if (realtime != null && realtime.getClose() > 0) {
            if (!bars.isEmpty() && bars.get(bars.size() - 1).getDate() != null
                    && bars.get(bars.size() - 1).getDate().equals(realtime.getDate())) {
                bars.set(bars.size() - 1, realtime);
            } else {
                bars.add(realtime);
            }
            portfolio.updatePositionPrice(code, realtime.getClose());
        }

        String stockName = getStockName(code);
        // [P1-2] 若有缓存的analysis则重用，否则重新计算（追加实时价后需重新分析）
        AnalysisResult analysis = cachedAnalysis != null ? cachedAnalysis : analyzer.analyze(code, stockName, bars);
        TradeSignal signal = strategy.generateSignal(code, stockName, bars, analysis, portfolio);

        if (signal.getSignalType() == TradeSignal.SignalType.HOLD) return;

        log.info("[信号] {} {} | {} | 强度:{} | {}",
                code, stockName, signal.getSignalType().getDescription(),
                signal.getStrength(), signal.getReason());

        // 执行信号
        if (signal.getSignalType().isBuySignal()) {
            executeBuy(signal, analysis, bars);
        } else if (signal.getSignalType().isSellSignal()) {
            executeSell(signal);
        }
    }

    /**
     * 执行模拟买入
     * @param bars K线数据（用于 ATR 风险仓位计算）
     */
    private void executeBuy(TradeSignal signal, AnalysisResult analysis, List<StockBar> bars) {
        String code = signal.getStockCode();
        double price = signal.getSuggestedPrice();
        if (price <= 0) return;
        // 确保 stockName 非空：signal 里的名称可能是空字符串，兜底用 getStockName
        if (signal.getStockName() == null || signal.getStockName().isEmpty()) {
            signal.setStockName(getStockName(code));
        }

        // ===== 开仓价格异常检测：校验信号价格是否在昨收±涨跌停幅度内 =====
        // 目的：防止数据源给出的错误价格（如跳空缺口数据异常、实时行情延迟）导致以异常价格下单。
        // 涨跌停幅度规则：
        //   - ST/ST*：±5%
        //   - 科创板(688)、创业板(30)、北交所(8/4)：±20%
        //   - 其他 A 股：±10%
        try {
            StockBar rtForPriceCheck = dataProvider.getRealTimeQuote(code);
            if (rtForPriceCheck != null && rtForPriceCheck.getClose() > 0
                    && Math.abs(rtForPriceCheck.getChangePercent()) < 25.0) { // changePercent 在合理范围内才推算
                double prevClose = rtForPriceCheck.getClose() / (1 + rtForPriceCheck.getChangePercent() / 100.0);
                if (prevClose > 0) {
                    // 判断涨跌停幅度
                    String stockNameForCheck = signal.getStockName() != null ? signal.getStockName() : "";
                    boolean isST = stockNameForCheck.contains("ST") || stockNameForCheck.contains("st");
                    boolean isScience = code.startsWith("688");          // 科创板
                    boolean isGEM     = code.startsWith("30");           // 创业板
                    boolean isBSE     = code.startsWith("8") || code.startsWith("4"); // 北交所
                    double limitPct;
                    if (isST) {
                        limitPct = 0.055; // ST 涨跌停 ±5%，加 0.5% 容差
                    } else if (isScience || isGEM || isBSE) {
                        limitPct = 0.205; // ±20%，加 0.5% 容差
                    } else {
                        limitPct = 0.105; // ±10%，加 0.5% 容差
                    }
                    double limitUp   = prevClose * (1 + limitPct);
                    double limitDown = prevClose * (1 - limitPct);
                    if (price > limitUp || price < limitDown) {
                        log.warn("[价格异常] {} 信号价格={}，昨收价估算={}，超出涨跌停范围[{},{}]，拒绝下单（数据源异常保护）",
                                code,
                                String.format("%.3f", price),
                                String.format("%.3f", prevClose),
                                String.format("%.3f", limitDown),
                                String.format("%.3f", limitUp));
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[价格异常] {} 获取实时行情失败，跳过价格合法性检查: {}", code, e.getMessage());
        }

        // 已持仓检查：
        //   - 普通 BUY 信号：跳过（避免重复开仓）
        //   - 加仓信号（reason 中含 "[加仓]" 标记）：允许通过，执行金字塔补仓
        boolean isAddPositionSignal = signal.getReason() != null && signal.getReason().contains("[加仓]");
        if (portfolio.hasPosition(code) && !isAddPositionSignal) return;

        // 持仓数量上限检查（加仓信号不新增持仓数量，跳过此限制）
        if (!isAddPositionSignal && portfolio.getPositions().size() >= topN) {
            // 若是强烈买入信号，暂存供仓位置换逻辑使用
            if (signal.getSignalType() == TradeSignal.SignalType.STRONG_BUY
                    && signal.getStrength() >= SWAP_MIN_NEW_STRENGTH
                    && analysis != null) {
                blockedStrongBuySignals.put(code, new Object[]{signal, analysis});
                log.debug("[置换候选] {} 强烈买入信号(强度{})被持仓上限阻止，暂存等待置换评估",
                        code, signal.getStrength());
            } else {
                log.info("[买入跳过] 已持 {} 只，达上限 {}，不新开仓：{}", portfolio.getPositions().size(), topN, code);
            }
            return;
        }

        // ===== 账户级日止损熔断：当日亏损 >= 2% 时全面停止买入 =====
        // 熔断逻辑：实时计算当日亏损比例；若达到阈值则置位熔断标志，当日内不再买入。
        // 止损卖出（executeSell）不受此逻辑影响，风险出清路径保持畅通。
        if (dailyStartAssets > 0) {
            double dailyLossPct = (portfolio.getTotalAssets() - dailyStartAssets) / dailyStartAssets;
            if (dailyLossPct <= DAILY_LOSS_CIRCUIT_BREAKER_PCT) {
                if (!dailyCircuitBroken) {
                    dailyCircuitBroken = true;
                    log.warn("[日止损熔断] 账户当日亏损 {}%，触发日止损熔断（阈值{}%），今日停止所有买入，仅保留止损卖出",
                            String.format("%.2f", dailyLossPct * 100),
                            String.format("%.0f", Math.abs(DAILY_LOSS_CIRCUIT_BREAKER_PCT * 100)));
                    pushService.sendMessage("[⚠️日止损熔断] " + username,
                            String.format("当日亏损已达 %.2f%%（阈值%.0f%%），今日停止所有买入，请关注持仓风险！",
                                    dailyLossPct * 100, Math.abs(DAILY_LOSS_CIRCUIT_BREAKER_PCT * 100)));
                }
                // [P3-1 优化] 同步检测大亏阈值（-3%），触发次日冷却期
                if (dailyLossPct <= LARGE_LOSS_COOLDOWN_THRESHOLD && largeLossCooldownTriggerDate == null) {
                    largeLossCooldownTriggerDate = LocalDate.now();
                    // 持久化到数据库，防止重启后冷却期状态丢失
                    persistence.saveLargelossCooldownDate(accountId, largeLossCooldownTriggerDate);
                    log.warn("[P3-1大亏冷却] 账户当日亏损 {}%（超过-3%%阈值），记录触发日={}，次日进入半冷却期（仅允许评分>={}的高质量信号开仓）",
                            String.format("%.2f", dailyLossPct * 100), largeLossCooldownTriggerDate,
                            COOLDOWN_MIN_SIGNAL_STRENGTH);
                    pushService.sendMessage("[⚠️大亏冷却] " + username,
                            String.format("当日亏损已达 %.2f%%（超-3%%阈值），明日进入半冷却期，仅接受评分≥%d的高质量信号！",
                                    dailyLossPct * 100, COOLDOWN_MIN_SIGNAL_STRENGTH));
                }
                log.info("[日止损熔断] 熔断中，拒绝买入 {}（当日亏损 {}%）",
                        code, String.format("%.2f", dailyLossPct * 100));
                return;
            }
        }
        // 若之前曾经触发熔断，当日不解除（即使亏损收窄也不允许再开仓）
        if (dailyCircuitBroken) {
            log.info("[日止损熔断] 熔断状态中，拒绝买入 {}", code);
            return;
        }

        // ===== [P3-1 优化] 大亏后次日冷却期检查 =====
        // 逻辑：若昨日（或前日）账户亏损>3%，今日进入半冷却期，只接受高质量信号（评分>=80）
        if (!isAddPositionSignal && largeLossCooldownTriggerDate != null) {
            LocalDate cooldownNextDay = largeLossCooldownTriggerDate.plusDays(1);
            if (LocalDate.now().equals(cooldownNextDay)) {
                // 今日处于半冷却期
                int signalStrength = signal.getStrength();
                if (signalStrength < COOLDOWN_MIN_SIGNAL_STRENGTH) {
                    log.info("[P3-1大亏冷却] {} 信号强度={}（<{}），昨日大亏冷却期内拒绝低质量开仓（昨日亏损>3%）",
                            code, signalStrength, COOLDOWN_MIN_SIGNAL_STRENGTH);
                    return;
                }
                log.info("[P3-1大亏冷却] {} 信号强度={}（>={}），昨日大亏冷却期但高质量信号允许通过",
                        code, signalStrength, COOLDOWN_MIN_SIGNAL_STRENGTH);
            } else if (LocalDate.now().isAfter(cooldownNextDay)) {
                // 冷却期已过，清除内存记录和数据库记录
                largeLossCooldownTriggerDate = null;
                persistence.saveLargelossCooldownDate(accountId, null);
                log.debug("[P3-1大亏冷却] 冷却期已过，恢复正常开仓，已清除持久化记录");
            }
        }

        // ===== 止损冷却过滤：止损后2小时内禁止新开仓（仅对新开仓有效，加仓不受限）=====
        // 目的：防止「止损→立刻追高→次日再止损」的死循环，强制系统冷静期
        if (!isAddPositionSignal) {
            long msSinceLastStop = System.currentTimeMillis() - lastStopLossTimeMs;
            if (lastStopLossTimeMs > 0 && msSinceLastStop < STOP_LOSS_COOLDOWN_MS) {
                long remainMinutes = (STOP_LOSS_COOLDOWN_MS - msSinceLastStop) / 60000;
                log.info("[止损冷却] {} 买入信号被止损冷却期拦截，距上次止损仅{}分钟，冷却期{}小时内禁止新开仓（剩余{}分钟）",
                        code, msSinceLastStop / 60000, STOP_LOSS_COOLDOWN_MS / 3600000, remainMinutes);
                return;
            }
        }

        // ===== 止损后同标的重入保护：止损后至少 2 个交易日才能重入同一标的 =====
        // 目的：防止「止损→次日信号再出→追回→再次止损」循环损耗本金。
        // 计算方式：按自然日距离（含今日）简单估算交易日，即 2 个自然日内（当天和次日）禁止重入。
        // 豁免：加仓信号不受此限制（加仓是对已持仓股的操作，不是新开仓重入）。
        if (!isAddPositionSignal) {
            LocalDate stopLossDate = stopLossReentryMap.get(code);
            if (stopLossDate != null) {
                long daysSinceStop = java.time.temporal.ChronoUnit.DAYS.between(stopLossDate, LocalDate.now());
                if (daysSinceStop < STOP_LOSS_REENTRY_MIN_DAYS) {
                    log.info("[重入保护] {} 曾于 {} 止损，距今仅 {} 天（需满 {} 天），拒绝重入以防止频繁反复止损",
                            code, stopLossDate, daysSinceStop, STOP_LOSS_REENTRY_MIN_DAYS);
                    return;
                } else {
                    // 超过保护期，清除保护记录，允许重入
                    stopLossReentryMap.remove(code);
                    log.debug("[重入保护] {} 止损已过 {} 天（>= {}天保护期），允许重入", code, daysSinceStop, STOP_LOSS_REENTRY_MIN_DAYS);
                }
            }
        }

        // ===== 大盘择时过滤（分级策略）=====
        // 【Level-1】大盘当日跌幅 >= 1.5%：全面封禁，系统性风险高，所有新开仓/加仓均停止
        // 【Level-2】仅 MA5<MA20（震荡偏弱但非大跌）：
        //   - 信号强度 >= MARKET_BEARISH_STRONG_SIGNAL_THRESHOLD（85）：个股强势，允许通过
        //   - 信号强度 < 85 或为加仓信号以外的普通新开仓：拦截，避免在弱势市买入弱信号
        // 目的：防止 A 股 MA5<MA20 长期满足导致全天零新开仓，错过个股独立行情
        if (isMarketBearishNow()) {
            // cachedIndexChangePct 由 isMarketBearishNow() 内部填充（无需二次请求）
            boolean bigDrop = cachedIndexChangePct <= MARKET_DROP_THRESHOLD * 100;
            if (bigDrop) {
                // Level-1：大跌全面封禁（含加仓）
                log.info("[大盘择时] {} 买入信号被大盘风险过滤拦截（大盘大跌{}%，全面暂停新开仓/加仓）",
                        code, String.format("%.2f", cachedIndexChangePct));
                return;
            }
            // Level-2：仅 MA5<MA20，按信号强度分级处理
            if (!isAddPositionSignal) {
                // 加仓信号豁免；纯新开仓判断信号强度
                int signalStrength = signal.getStrength();
                if (signalStrength < MARKET_BEARISH_STRONG_SIGNAL_THRESHOLD) {
                    log.info("[大盘择时] {} 买入信号被大盘风险过滤拦截（MA5<MA20偏弱市，信号强度{}分<{}分阈值，暂停弱信号新开仓）（指数涨跌={}%）",
                            code, signalStrength, MARKET_BEARISH_STRONG_SIGNAL_THRESHOLD,
                            String.format("%.2f", cachedIndexChangePct));
                    return;
                }
                log.info("[大盘择时] {} 强信号(强度{}分>={})在MA5<MA20弱势市中仍允许建仓（指数涨跌={}%）",
                        code, signalStrength, MARKET_BEARISH_STRONG_SIGNAL_THRESHOLD,
                        String.format("%.2f", cachedIndexChangePct));
            }
        }

        // ===== [P0-2] 大盘情绪评分过滤：情绪悲观时动态提高买入信号阈值 =====
        // 逻辑：当 marketSentimentScore < 35（悲观市场，多数股票下跌+成交量萎缩），
        //   将大盘 MA5<MA20 弱势市的信号强度阈值从85提高到90，执行更严格的过滤。
        // 目的：在大盘 MA5<MA20 且情绪极度悲观时，只允许极强信号（≥90分）通过，
        //   进一步降低在弱势市场中买入的频率，减少在普跌行情中的无谓损耗。
        // 豁免：若大盘 MA5>=MA20（正常或强势市），情绪评分不额外限制买入。
        if (!isAddPositionSignal && marketBearish && !marketDeepBearish && marketSentimentScore < 35) {
            int signalStrength = signal.getStrength();
            // 情绪悲观时阈值提高到 90
            final int SENTIMENT_PESSIMISTIC_THRESHOLD = 90;
            if (signalStrength < SENTIMENT_PESSIMISTIC_THRESHOLD) {
                log.info("[P0-2情绪过滤] {} 大盘情绪评分={}（<35悲观），弱势市信号阈值提高至{}分，" +
                        "当前信号强度{}分不足，拒绝买入", code, marketSentimentScore,
                        SENTIMENT_PESSIMISTIC_THRESHOLD, signalStrength);
                return;
            }
            log.info("[P0-2情绪过滤] {} 情绪评分={}悲观，但信号强度={}分（>={}），允许建仓",
                    code, marketSentimentScore, signalStrength, SENTIMENT_PESSIMISTIC_THRESHOLD);
        }

        // ===== 当日涨幅过滤：差异化阈值（主板5%，科创板/创业板10%） =====
        // [P2-1 优化] A股涨跌停幅度差异化：
        //   - 科创板（688xxx）、创业板（300xxx）：涨跌停限制为±20%，追涨红线放宽至10%
        //   - 主板（沪深主板、北交所）：涨跌停限制为±10%，追涨红线维持5%
        // 目的：避免「追高买入高位股→次日回调止损」的亏损模式，同时避免把科创/创业板5~10%
        //       正常强势行情误判为「过度追高」而漏掉优质机会。
        // 豁免：加仓信号不受此限制（持仓内股票的加仓时机由策略独立判断）
        if (!isAddPositionSignal) {
            try {
                StockBar rtForFilter = dataProvider.getRealTimeQuote(code);
                if (rtForFilter != null) {
                    // 判断是否为科创板/创业板（涨跌幅限制为±20%）
                    boolean isHighLimitBoard = code.startsWith("688") || code.startsWith("300");
                    double changePctLimit = isHighLimitBoard ? 10.0 : 5.0;
                    double changePct = rtForFilter.getChangePercent();
                    if (changePct > changePctLimit) {
                        log.info("[涨幅过滤] {} {}当日涨幅={}%，超过{}%追涨红线，拒绝买入（防追高）",
                                code, isHighLimitBoard ? "【科创/创业板】" : "",
                                String.format("%.2f", changePct), (int) changePctLimit);
                        return;
                    }
                }
            } catch (Exception e) {
                log.debug("[涨幅过滤] {} 获取实时行情异常，跳过涨幅检查: {}", code, e.getMessage());
            }
        }

        // ===== [P1-3 优化] 动量高位反转过滤：20日涨幅>15%不买（追涨高位风险极高）=====
        // 背景：20日涨幅>15%（约等于3个交易周内上涨15%）属于短期动量高位，
        //   均值回归效应显著，追入后往往伴随强烈回调（即"追涨杀跌"现象）。
        //   历史成交数据显示，追入20日涨幅>15%股票的止损率明显高于普通标的。
        // 豁免：加仓信号不受此限制；竞价封板策略不受此限制（其买入逻辑本身就是追涨）。
        if (!isAddPositionSignal && !(strategy instanceof com.stocktrader.strategy.AuctionLimitUpStrategy)) {
            try {
                LocalDate endDate20 = LocalDate.now();
                LocalDate startDate20 = endDate20.minusMonths(2);
                List<StockBar> bars20 = dataProvider.getDailyBars(code, startDate20, endDate20, StockBar.AdjustType.FORWARD);
                if (bars20 != null && bars20.size() >= 20) {
                    // 取最近20根K线（倒数第21根为20日前的收盘价）
                    int sz20 = bars20.size();
                    double price20dAgo = bars20.get(sz20 - 21 >= 0 ? sz20 - 21 : 0).getClose();
                    double priceNow    = bars20.get(sz20 - 1).getClose();
                    if (price20dAgo > 0) {
                        double gain20d = (priceNow - price20dAgo) / price20dAgo * 100;
                        // 科创板/创业板（±20%涨跌幅）适当放宽阈值至20%
                        boolean isHighLimitBoard20 = code.startsWith("688") || code.startsWith("300");
                        double gain20dLimit = isHighLimitBoard20 ? 20.0 : 15.0;
                        if (gain20d > gain20dLimit) {
                            log.info("[高位反转过滤] {} 近20日涨幅={}%，超过{}%高位阈值，拒绝买入（防追涨高位均值回归）",
                                    code, String.format("%.1f", gain20d), (int) gain20dLimit);
                            return;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[高位反转过滤] {} 获取K线数据失败，跳过20日涨幅检查: {}", code, e.getMessage());
            }
        }

        // ===== 板块止损保护：当日止损过的行业禁止再买入同行业股票 =====
        // 豁免：加仓信号不受此限制（持仓内股票加仓由策略独立判断，不应因同行业他股止损而拦截）
        if (!isAddPositionSignal && !stopLossIndustriesToday.isEmpty()) {
            try {
                com.stocktrader.model.Stock stockInfoForBuy = dataProvider.getStockInfo(code);
                if (stockInfoForBuy != null && stockInfoForBuy.getIndustry() != null
                        && stopLossIndustriesToday.contains(stockInfoForBuy.getIndustry())) {
                    log.info("[板块保护] {} 行业「{}」今日已有止损记录，拒绝买入（板块轮动保护）",
                            code, stockInfoForBuy.getIndustry());
                    return;
                }
            } catch (Exception e) {
                log.debug("[板块保护] 获取 {} 行业信息失败，跳过行业检查: {}", code, e.getMessage());
            }
        }

        // ===== [P1-1 优化] 行业分散约束：持仓中同行业不超过1只 =====
        // 目的：防止账户被单一行业板块集中踩雷，降低板块系统性风险的暴露。
        // 逻辑：买入前检查当前持仓，若已有同行业股票则拒绝买入新的同行业股。
        // 豁免：加仓信号不受此限制（对已持仓的同行业股加仓属于策略正常行为）。
        if (!isAddPositionSignal && !portfolio.getPositions().isEmpty()) {
            try {
                com.stocktrader.model.Stock newStockInfo = dataProvider.getStockInfo(code);
                if (newStockInfo != null && newStockInfo.getIndustry() != null
                        && !newStockInfo.getIndustry().isEmpty()) {
                    String newIndustry = newStockInfo.getIndustry();
                    // 遍历当前持仓，检查是否已有同行业
                    for (Position existingPos : portfolio.getPositions().values()) {
                        if (existingPos.getStockCode().equals(code)) continue; // 跳过自身
                        try {
                            com.stocktrader.model.Stock existingInfo =
                                    dataProvider.getStockInfo(existingPos.getStockCode());
                            if (existingInfo != null && newIndustry.equals(existingInfo.getIndustry())) {
                                log.info("[行业分散] {} 行业「{}」已持有同行业股票 {} {}，拒绝买入（同行业持仓上限1只）",
                                        code, newIndustry,
                                        existingPos.getStockCode(), existingPos.getStockName());
                                return;
                            }
                        } catch (Exception ignored) {
                            // 获取已持仓股行业信息失败时跳过该持仓，不因此阻断新买入
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[行业分散] {} 获取行业信息失败，跳过行业分散检查: {}", code, e.getMessage());
            }
        }

        // ===== [P0-1 优化] 分钟线多维买入确认（仅短线策略）=====
        // 增强逻辑：在原有"最新阴线+3根连续下行"基础上新增：
        //   ① 5分钟线成交量萎缩过滤：近3根5分钟量持续缩量（每根量<前根×80%），无量下跌不买
        //   ② 5分钟线MACD方向确认：5分钟DIF < DEA（分钟级MACD偏空），不与日线反向操作
        //   ③ 极端跳水过滤：最新3根5分钟收盘价累计跌幅 > -1.5%，说明日内出现快速杀跌
        // 触发规则：以上3条满足任意2条，即视为日内趋势偏弱，拒绝买入
        // 目的：过滤"日线多头但盘中正在向下运行"的虚假信号，降低日内抄底风险。
        // 豁免：仅对 DayTradingStrategy 生效；中长线/其他策略不受分钟线干扰。
        if (!isAddPositionSignal && strategy instanceof DayTradingStrategy) {
            try {
                List<StockBar> min5Bars = dataProvider.getMinuteBars(code, StockBar.BarPeriod.MIN_5, 15);
                if (min5Bars != null && min5Bars.size() >= 6) {
                    int sz = min5Bars.size();
                    StockBar latestMin5 = min5Bars.get(sz - 1);

                    // ① 原有逻辑：最新阴线 + 近3根连续下行
                    boolean latestIsDown = latestMin5.getClose() < latestMin5.getOpen();
                    boolean continuousDown = sz >= 3
                            && min5Bars.get(sz - 1).getClose() < min5Bars.get(sz - 2).getClose()
                            && min5Bars.get(sz - 2).getClose() < min5Bars.get(sz - 3).getClose();
                    boolean basicWeakSignal = latestIsDown && continuousDown;

                    // ② [P0-1新增] 5分钟成交量萎缩：近3根量逐根递减（有序缩量，空头走弱特征）
                    boolean volumeShrinking = false;
                    if (sz >= 4) {
                        long v1 = min5Bars.get(sz - 3).getVolume(); // 3根前
                        long v2 = min5Bars.get(sz - 2).getVolume(); // 2根前
                        long v3 = min5Bars.get(sz - 1).getVolume(); // 最新
                        // 连续3根量递减，且最新量 < 3根前量的50%（严重缩量+下行=主动卖出衰竭前兆）
                        volumeShrinking = (v1 > 0) && (v2 < v1 * 0.8) && (v3 < v2 * 0.8) && (v3 < v1 * 0.5);
                    }

                    // ③ [P0-1新增] 5分钟MACD空头确认：计算最近12根5分钟的简易DIF判断
                    boolean min5MacdBearish = false;
                    if (sz >= 12) {
                        // 用最近12根收盘价计算简化EMA(6) 和 EMA(12)，判断DIF方向
                        double ema6 = 0, ema12 = 0;
                        double k6 = 2.0 / 7.0, k12 = 2.0 / 13.0;
                        // 用前6根初始化EMA6，前12根初始化EMA12
                        double sum6 = 0, sum12 = 0;
                        for (int i = sz - 12; i < sz - 6; i++) sum12 += min5Bars.get(i).getClose();
                        for (int i = sz - 6; i < sz; i++) { sum6 += min5Bars.get(i).getClose(); sum12 += min5Bars.get(i).getClose(); }
                        ema6 = sum6 / 6.0; ema12 = sum12 / 12.0;
                        // 再用最新一根做一步EMA更新
                        double close0 = min5Bars.get(sz - 1).getClose();
                        ema6  = close0 * k6  + ema6  * (1 - k6);
                        ema12 = close0 * k12 + ema12 * (1 - k12);
                        double dif = ema6 - ema12;
                        // 同时要求DIF < 0（空头区域）且近2根DIF方向向下（当前DIF < 前一根）
                        double close1 = min5Bars.get(sz - 2).getClose();
                        double ema6Prev  = sum6 / 6.0 * (1 - k6)  + close1 * k6;
                        double ema12Prev = sum12 / 12.0 * (1 - k12) + close1 * k12;
                        double difPrev = ema6Prev - ema12Prev;
                        min5MacdBearish = (dif < 0) && (dif < difPrev); // DIF在0轴下方且继续下行
                    }

                    // ④ [P0-1新增] 日内快速杀跌：最近3根5分钟累计跌幅 > -1.5%
                    boolean rapidDrop = false;
                    if (sz >= 3) {
                        double priceRef = min5Bars.get(sz - 3).getOpen(); // 3根前开盘价作为参考
                        if (priceRef > 0) {
                            double dropPct = (latestMin5.getClose() - priceRef) / priceRef;
                            rapidDrop = dropPct < -0.015; // 3根内跌超1.5%
                        }
                    }

                    // 触发规则：基础弱信号 OR (4条中满足任意2条)
                    int weakCount = (basicWeakSignal ? 1 : 0) + (volumeShrinking ? 1 : 0)
                            + (min5MacdBearish ? 1 : 0) + (rapidDrop ? 1 : 0);
                    if (basicWeakSignal || weakCount >= 2) {
                        log.info("[P0-1分钟线过滤] {} 日内趋势偏弱（{}条弱信号），拒绝短线买入" +
                                " | 基础弱={} 缩量={} 5mMACD空={} 快速杀跌={}",
                                code, weakCount, basicWeakSignal, volumeShrinking, min5MacdBearish, rapidDrop);
                        return;
                    }
                    log.debug("[P0-1分钟线过滤] {} 5分钟线验证通过（{}条弱信号<2），允许买入", code, weakCount);
                }
            } catch (Exception e) {
                log.debug("[P0-1分钟线过滤] {} 获取5分钟K线失败，跳过分钟线方向确认: {}", code, e.getMessage());
            }
        }

        // ===== 仓位动态调整：均仓分配 + 策略信号强度分级 + ATR风险上限双重控制 =====
        // Step1: [P2-4 优化] 均仓资金分配：剩余资金 / 剩余空位数，保证每个仓位均等
        //   - 剩余空位数 = topN - 当前持仓数（不含本次待买入的）
        //   - 单仓均等金额 = 可用资金 / max(剩余空位数, 1)
        //   - 策略仓位比例（posRatio）在此基础上作为调节系数（强信号可用满均仓，弱信号用一半）
        //   目的：避免「先开仓的占用大量资金，后开仓时资金不足」的不均衡问题
        int currentPositions = portfolio.getPositions().size();
        int remainingSlots = Math.max(topN - currentPositions, 1); // 至少1，防止除零
        double availableCash = portfolio.getAvailableCash();
        // 均仓单位资金：总可用资金均分到剩余空位
        double equalShareAmount = availableCash / remainingSlots;
        // 策略信号强度调节系数（强度90+用满均仓，80~89用7成，其他用5成）
        double posRatio = strategy.calculatePositionSize(signal, portfolio);
        // 最终买入金额：均仓基础金额 × 信号强度系数，但不超过maxPositionRatio×总资产
        double maxSingleAmount = portfolio.getTotalAssets()
                * (strategyConfig != null ? strategyConfig.getMaxPositionRatio() : 0.33);
        double buyAmount = Math.min(equalShareAmount * posRatio, maxSingleAmount);
        // 确保至少尝试买入1手（资金检查后做兜底）
        buyAmount = Math.max(buyAmount, price * 100); // 至少1手
        int quantity = (int) (buyAmount / price / 100) * 100;
        if (quantity == 0 && availableCash >= price * 100) {
            quantity = (int) (availableCash / price / 100) * 100;
        }
        log.debug("[均仓分配] {} 当前持仓{}/{}，剩余空位{}，均仓金额={} × 信号系数{}={} 实际建仓{}股",
                code, currentPositions, topN, remainingSlots,
                String.format("%.0f", equalShareAmount), String.format("%.2f", posRatio),
                String.format("%.0f", buyAmount), quantity);
        // Step2：ATR风险上限保护 —— 单笔最大亏损不超过总资金的1%
        // 只在有K线数据时启用；若K线不足则跳过（降级到纯比例建仓）
        if (bars != null && bars.size() >= 14 && quantity > 0) {
            int riskBasedQty = com.stocktrader.analysis.AtrStopLoss.calcPositionByRisk(
                    portfolio.getTotalAssets(), 0.01, bars, 14, 1.5, price);
            if (riskBasedQty > 0 && riskBasedQty < quantity) {
                log.info("[ATR风控] {} 信号建仓{}股→按ATR1%风险限制收窄至{}股（ATR止损后最大亏损<=总资金1%）",
                        code, quantity, riskBasedQty);
                quantity = riskBasedQty;
            }
        }
        if (quantity <= 0) {
            log.warn("[买入失败] {} 资金不足或价格过高（价格:{} 可用资金:{}）",
                    code, String.format("%.2f", price), String.format("%.2f", availableCash));
            return;
        }

        double amount = quantity * price;
        String exchange = code.startsWith("6") ? "SH" : "SZ";
        FeeCalculator.FeeDetail fee = feeCalculator.calculateBuyFee(amount, exchange);

        // ===== 执行买入：实盘模式走 QMT，模拟模式直接修改 Portfolio =====
        String stockName = signal.getStockName() != null && !signal.getStockName().isEmpty()
                ? signal.getStockName() : code;
        Order order;
        boolean ok;
        LiveBrokerAdapter adapter = liveBrokerAdapter;
        if (adapter != null && adapter.isLiveAvailable()) {
            // --- 实盘路径：QMT 下单，等待成交后写回 Portfolio ---
            log.info("[实盘买入] 触发 {} {}股 @{}", code, quantity, String.format("%.2f", price));
            order = adapter.submitBuyLive(portfolio, code, stockName,
                    price, quantity, signal.getStrategyName(), signal.getReason());
            ok = (order != null);
            if (!ok) {
                log.warn("[实盘买入] {} 下单未成交，本次买入取消", code);
                return;
            }
            // 用实际成交结果刷新本地变量，用于后续报告
            amount   = order.getAmount();
            quantity = order.getFilledQuantity();
        } else {
            // --- 模拟路径：直接构建 Order 并修改 Portfolio ---
            order = Order.builder()
                    .orderId(UUID.randomUUID().toString())
                    .stockCode(code)
                    .stockName(stockName)
                    .orderType(Order.OrderType.BUY)
                    .status(Order.OrderStatus.FILLED)
                    .price(price).quantity(quantity)
                    .filledPrice(price).filledQuantity(quantity)
                    .amount(amount)
                    .commission(fee.commission).stampTax(0)
                    .transferFee(fee.transferFee).totalFee(fee.total)
                    .createTime(LocalDateTime.now()).filledTime(LocalDateTime.now())
                    .strategyName(signal.getStrategyName())
                    .remark(signal.getReason())
                    .build();
            ok = portfolio.executeBuy(order);
        }

        if (ok) {
            // ===== 将策略层计算的 ATR 止损价记录到持仓中 =====
            // 目的：让分钟级快速止损（quickCheckOnePosition）也能用相同的 ATR 止损标准，
            // 彻底消除「策略层用 ATR+固定取较紧」vs「分钟级只用固定比例」的盲区。
            // signal.getStopLossPrice() > 0 表示策略层已计算 ATR 止损价；
            // = 0 时表示策略未提供（旧代码路径），持仓 atrStopPrice 保持 0，分钟级降级为固定止损。
            if (signal.getStopLossPrice() > 0) {
                Position boughtPos = portfolio.getPosition(code);
                if (boughtPos != null) {
                    boughtPos.setAtrStopPrice(signal.getStopLossPrice());
                    log.info("[ATR止损记录] {} {} 买入成功，策略ATR止损价={} 写入持仓，分钟级止损与全量扫描标准一致",
                            code, stockName, String.format("%.2f", signal.getStopLossPrice()));
                }
            }
            // T+1：买入当日不可卖，次日开盘后自动解锁
            totalBuyCount++;
            printTradeReport("买入", code, stockName, price, quantity, amount, fee.total,
                    signal.getReason(), analysis);
            saveTradeReportFile("买入", order, signal.getReason(), analysis, 0, 0);
            persistence.save(portfolio);
            // 微信推送买入信号
            pushService.sendBuySignal(username, code, stockName,
                    price, quantity, amount, signal.getReason(),
                    portfolio.getTotalAssets(), portfolio.getAvailableCash(),
                    buildPositionSummary());
        }
    }

    /**
     * 执行模拟卖出
     */
    private void executeSell(TradeSignal signal) {
        String code = signal.getStockCode();
        Position pos = portfolio.getPosition(code);
        if (pos == null || pos.getAvailableQuantity() <= 0) return;
        // 确保 stockName 非空
        if (signal.getStockName() == null || signal.getStockName().isEmpty()) {
            signal.setStockName(getStockName(code));
        }

        double price = signal.getSuggestedPrice();
        int availQty = pos.getAvailableQuantity();
        int quantity;

        String reason = signal.getReason() != null ? signal.getReason() : "";
        if (signal.getSignalType() == TradeSignal.SignalType.STOP_LOSS) {
            // 止损：全部卖出，并更新冷却期起点时间（防止止损后立即追高）
            lastStopLossTimeMs = System.currentTimeMillis();
            log.debug("[止损冷却] 止损卖出 {}，冷却期 {} 小时内禁止新开仓", code, STOP_LOSS_COOLDOWN_MS / 3600000);
            // [P0-1 优化] 止损后立即强制刷新大盘状态：
            // 将大盘缓存时间戳重置为0，使下次 processBuySignal() 必须重新拉取大盘行情。
            // 背景：止损往往因为盘面突变，此时大盘很可能已进入弱势；
            //   若不强制刷新，5分钟缓存期内系统仍用旧的"大盘健康"状态判断，继续放行买入信号，
            //   导致"止损→换仓→再止损"的连续亏损循环。
            marketStatusLastUpdate = 0L;
            log.info("[P0-1止损刷新] {} 止损触发，强制过期大盘状态缓存，下次买入前将重新判断大盘健康度", code);
            // 记录止损日期到重入保护表（至少 STOP_LOSS_REENTRY_MIN_DAYS 个交易日后才允许重入同一标的）
            stopLossReentryMap.put(code, LocalDate.now());
            log.debug("[重入保护] 止损卖出 {}，记录止损日 {}，至少 {} 个交易日后才允许重入",
                    code, LocalDate.now(), STOP_LOSS_REENTRY_MIN_DAYS);
            // 记录止损股票所属行业到当日黑名单（板块止损保护）
            try {
                com.stocktrader.model.Stock stockInfo = dataProvider.getStockInfo(code);
                if (stockInfo != null && stockInfo.getIndustry() != null && !stockInfo.getIndustry().isEmpty()) {
                    stopLossIndustriesToday.add(stockInfo.getIndustry());
                    log.info("[板块保护] {} 止损，封锁行业「{}」当日内不再买入同行业股票", code, stockInfo.getIndustry());
                }
            } catch (Exception e) {
                log.debug("[板块保护] 获取 {} 行业信息失败: {}", code, e.getMessage());
            }
            quantity = availQty;
        } else if (signal.getSignalType() == TradeSignal.SignalType.TAKE_PROFIT
                && reason.contains("清仓")) {
            // 清仓止盈（含尾盘清仓锁利）：全部卖出
            quantity = availQty;
        } else if (signal.getSignalType() == TradeSignal.SignalType.SELL
                && reason.contains("70%")) {
            // 尾盘减仓70%：卖出70%仓位，保留30%（最少100股，最多不超过可用量）
            quantity = Math.max((int)(availQty * 0.7 / 100) * 100, 100);
            if (quantity > availQty) quantity = availQty;
        } else if (signal.getSignalType() == TradeSignal.SignalType.TAKE_PROFIT
                || signal.getSignalType() == TradeSignal.SignalType.SELL) {
            // 做T减仓 / 普通卖出：卖出一半，最少100股
            quantity = Math.max((availQty / 2 / 100) * 100, 100);
            if (quantity > availQty) quantity = availQty;
        } else {
            quantity = availQty;
        }

        String sellStockName = signal.getStockName() != null && !signal.getStockName().isEmpty()
                ? signal.getStockName() : code;
        double avgCost = pos.getAvgCost();

        // ===== 执行卖出：实盘模式走 QMT，模拟模式直接修改 Portfolio =====
        Order order;
        boolean ok;
        LiveBrokerAdapter sellAdapter = liveBrokerAdapter;
        if (sellAdapter != null && sellAdapter.isLiveAvailable()) {
            // --- 实盘路径 ---
            log.info("[实盘卖出] 触发 {} {}股 @{}", code, quantity, String.format("%.2f", price));
            order = sellAdapter.submitSellLive(portfolio, code, sellStockName,
                    price, quantity, signal.getStrategyName(), signal.getReason());
            ok = (order != null);
            if (!ok) {
                log.warn("[实盘卖出] {} 下单未成交，本次卖出取消", code);
                return;
            }
            // 用实际成交结果刷新本地变量
            price    = order.getFilledPrice() > 0 ? order.getFilledPrice() : price;
            quantity = order.getFilledQuantity();
        } else {
            // --- 模拟路径 ---
            String exchange = code.startsWith("6") ? "SH" : "SZ";
            FeeCalculator.FeeDetail fee = feeCalculator.calculateSellFee(quantity * price, exchange);
            order = Order.builder()
                    .orderId(UUID.randomUUID().toString())
                    .stockCode(code)
                    .stockName(sellStockName)
                    .orderType(Order.OrderType.SELL)
                    .status(Order.OrderStatus.FILLED)
                    .price(price).quantity(quantity)
                    .filledPrice(price).filledQuantity(quantity)
                    .amount(quantity * price)
                    .commission(fee.commission).stampTax(fee.stampTax)
                    .transferFee(fee.transferFee).totalFee(fee.total)
                    .createTime(LocalDateTime.now()).filledTime(LocalDateTime.now())
                    .strategyName(signal.getStrategyName())
                    .remark(signal.getReason())
                    .build();
            ok = portfolio.executeSell(order);
        }

        if (ok) {
            double amount   = order.getAmount();
            double totalFee = order.getTotalFee();
            double pnl      = (price - avgCost) * quantity - totalFee;
            double pnlRate  = avgCost > 0 ? (price - avgCost) / avgCost * 100 : 0;
            cumulativeRealizedPnl += pnl;
            if (pnl > 0) winSellCount++;

            totalSellCount++;
            printTradeReport("卖出", code, sellStockName, price, quantity, amount, totalFee,
                    signal.getReason(), null);
            printPnlReport(code, sellStockName, avgCost, price, quantity, pnl);
            saveTradeReportFile("卖出", order, signal.getReason(), null, pnl, pnlRate);
            persistence.save(portfolio);
            // 微信推送卖出信号
            double totalReturn = (portfolio.getTotalAssets() - initialCapital) / initialCapital * 100;
            pushService.sendSellSignal(username, code, sellStockName,
                    price, avgCost, quantity, amount, pnl, pnlRate,
                    signal.getReason(), totalReturn, buildPositionSummary());

            // 卖出后尝试补充新标的
            if (portfolio.getPositions().size() < topN) {
                log.info("[换仓] 卖出 {} 后持仓不足 {} 只，尝试选补新标的...", code, topN);
                refreshWatchlist();
            }
        }
    }

    /**
     * 仓位置换逻辑：用更强的新股信号替换当前持仓中最弱的股票（支持一轮多次置换）
     * <p>
     * 触发条件（全部满足）：
     *   1. 本轮扫描存在被阻止的 STRONG_BUY 信号（强度 >= SWAP_MIN_NEW_STRENGTH）
     *   2. 新股趋势不能是下跌（DOWN/STRONG_DOWN），避免换入下跌中的股票
     *   3. 新股综合评分 比 最弱持仓评分 高出 SWAP_MIN_SCORE_ADVANTAGE 以上
     *   4. 最弱持仓浮动亏损不超过 SWAP_MAX_LOSS_RATE（-5%，避免深度割肉）
     *   5. 最弱持仓满足 T+1（今日可卖）
     *   6. 14:45 之前（给新买入预留足够的当日时间）
     *   7. 新股不在换仓冷却期内（24小时内不重复换入同一只股票）
     * <p>
     * 一轮最多执行 SWAP_MAX_PER_ROUND 次置换，避免全仓在一轮内被全部替换。
     * 持仓评分优先使用 holdingScoreCache（大盘扫描时实时分析），
     * 无缓存时降级使用 screenResults，再无则默认50分。
     * </p>
     */
    private void tryPositionSwap() {
        if (blockedStrongBuySignals.isEmpty()) return;
        if (portfolio.getPositions().isEmpty()) return;

        // 【修复7】尾盘截止时间从 14:30 放宽到 14:45
        LocalTime now = LocalTime.now();
        if (now.isAfter(LocalTime.of(14, 45))) {
            log.debug("[置换跳过] 尾盘14:45后不做仓位置换");
            return;
        }

        // [P0-2 优化] 深度熊市（MA5<MA20<MA60）全面禁止换仓
        // 原因：在趋势性下跌行情中，换仓 = 先卖（锁定亏损）+ 再买（大概率继续亏损），
        //   形成"越换越亏"的死亡螺旋。与其主动换仓，不如守住现有仓位等待趋势反转。
        // 注意：isMarketBearishNow() 有5分钟缓存，此处直接用已缓存的标志位，不额外调用。
        if (marketDeepBearish) {
            log.info("[置换跳过-深度熊市] MA5<MA20<MA60 三线空头排列，深度熊市禁止换仓操作，避免越换越亏");
            return;
        }

        // 本轮已执行的置换次数
        int swapCount = 0;
        // 每次置换后，剩余候选信号池（已置换过的从池中移除）
        Map<String, Object[]> remainingCandidates = new LinkedHashMap<>(blockedStrongBuySignals);

        while (swapCount < SWAP_MAX_PER_ROUND && !remainingCandidates.isEmpty()) {

            // ===== Step A：找出当前持仓中「最弱」的一只 =====
            Position weakestPos = null;
            int weakestScore = Integer.MAX_VALUE;
            double weakestProfitRate = Double.MAX_VALUE;

            for (Position pos : portfolio.getPositions().values()) {
                // T+1 检查：今日买入的不可卖
                if (pos.getAvailableQuantity() <= 0) continue;

                // 持仓保护期：买入不足3个交易日的仓位不参与置换（P2优化：由2天延长至3天），
                // 让新买入的股票至少运行3个交易日再评估，避免刚买就因短期波动被换出
                if (pos.getLastBuyDate() != null) {
                    long holdDays = java.time.temporal.ChronoUnit.DAYS.between(pos.getLastBuyDate(), LocalDate.now());
                    if (holdDays < 3) {
                        log.debug("[置换跳过] {} 持仓仅{}天（保护期3交易日），不参与置换", pos.getStockCode(), holdDays);
                        continue;
                    }
                }

                double profitRate = pos.getAvgCost() > 0
                        ? (pos.getCurrentPrice() - pos.getAvgCost()) / pos.getAvgCost() : 0;

                // 亏损超过容忍线的不考虑（不强制深度割肉，由止损逻辑处理）
                if (profitRate < SWAP_MAX_LOSS_RATE) continue;

                // 【修复1】优先使用 holdingScoreCache（大盘扫描时实时分析的评分）
                int posScore;
                if (holdingScoreCache.containsKey(pos.getStockCode())) {
                    posScore = holdingScoreCache.get(pos.getStockCode());
                } else {
                    // 降级：从 screenResults 中查找
                    posScore = 50; // 默认50（不在任何已知结果里，说明已不被看好）
                    for (StockScreener.ScreenResult sr : screenResults) {
                        if (sr.stockCode.equals(pos.getStockCode())) {
                            posScore = sr.techScore;
                            break;
                        }
                    }
                }

                // 综合排序：优先换亏损率最高的，其次换评分最低的
                boolean isWeaker = false;
                if (weakestPos == null) {
                    isWeaker = true;
                } else if (profitRate < weakestProfitRate - 0.005) {
                    isWeaker = true; // 亏损更多
                } else if (Math.abs(profitRate - weakestProfitRate) <= 0.005 && posScore < weakestScore) {
                    isWeaker = true; // 盈亏相近时评分更低
                }

                if (isWeaker) {
                    weakestPos = pos;
                    weakestScore = posScore;
                    weakestProfitRate = profitRate;
                }
            }

            if (weakestPos == null) {
                log.debug("[置换跳过] 所有持仓均为T+1锁定或亏损超限，不做置换");
                break;
            }

            // ===== Step B：从候选中找出最强的、符合条件的新股信号 =====
            TradeSignal bestNewSignal = null;
            AnalysisResult bestNewAnalysis = null;
            int bestNewScore = 0;
            long nowMs = System.currentTimeMillis();

            for (Map.Entry<String, Object[]> entry : remainingCandidates.entrySet()) {
                TradeSignal sig = (TradeSignal) entry.getValue()[0];
                AnalysisResult ana = (AnalysisResult) entry.getValue()[1];

                // 【修复5】过滤下跌趋势的候选（避免换入正在下跌的股票）
                if (ana.getTrend() == AnalysisResult.TrendDirection.DOWN
                        || ana.getTrend() == AnalysisResult.TrendDirection.STRONG_DOWN) {
                    log.debug("[置换过滤] {} 趋势={}，不作为置换候选", sig.getStockCode(),
                            ana.getTrend().getDescription());
                    continue;
                }

                // 【修复6】检查冷却期：24小时内换出过的股票不再换入
                Long cooldownTs = swapCooldownMap.get(sig.getStockCode());
                if (cooldownTs != null && nowMs - cooldownTs < SWAP_COOLDOWN_MS) {
                    log.debug("[置换过滤] {} 在冷却期内（{}小时前换出），跳过",
                            sig.getStockCode(),
                            String.format("%.1f", (nowMs - cooldownTs) / 3600000.0));
                    continue;
                }

                // 【P1-2 换仓冷却验证】候选股需连续 SWAP_MIN_QUALIFY_DAYS 天评分达标才可换入
                // 防止「偶然冒头一天就被换入、次日回落再被换出」的频繁换仓损耗
                int qualifyDays = candidateQualifyDays.getOrDefault(sig.getStockCode(), 0);
                if (qualifyDays < SWAP_MIN_QUALIFY_DAYS) {
                    log.debug("[置换过滤] {} 连续达标仅{}天（需>={} 天），暂不换入（连续验证保护）",
                            sig.getStockCode(), qualifyDays, SWAP_MIN_QUALIFY_DAYS);
                    continue;
                }

                int newScore = ana.getOverallScore();
                if (newScore > bestNewScore) {
                    bestNewScore = newScore;
                    bestNewSignal = sig;
                    bestNewAnalysis = ana;
                }
            }

            if (bestNewSignal == null) {
                log.debug("[置换跳过] 无符合条件的新股候选（趋势或冷却期过滤后为空）");
                break;
            }

            // ===== [P2-3 优化] Step B+：5分钟K线方向确认 =====
            // 目的：换仓是高成本操作（双边手续费约0.25%），必须确认候选股日内也在向上运行，
            //   而非日线虽然多头但盘中正在走弱（日内卖压重，换入后立即被继续拉低）。
            // 条件：最新5分钟K线非阴线 且 近3根5分钟K线收盘价非连续下行
            // 豁免：无法获取5分钟数据时不阻断换仓（降级为仅靠日线判断）
            if (strategy instanceof DayTradingStrategy || strategy instanceof IntradayTradingStrategy) {
                String swapCandCode = bestNewSignal.getStockCode();
                try {
                    List<StockBar> swapMin5 = dataProvider.getMinuteBars(swapCandCode, StockBar.BarPeriod.MIN_5, 10);
                    if (swapMin5 != null && swapMin5.size() >= 4) {
                        int m5sz = swapMin5.size();
                        StockBar latestM5 = swapMin5.get(m5sz - 1);
                        boolean latestIsDown = latestM5.getClose() < latestM5.getOpen();
                        // 近3根5分钟K线连续下行
                        boolean continuousM5Down = m5sz >= 3
                                && swapMin5.get(m5sz - 1).getClose() < swapMin5.get(m5sz - 2).getClose()
                                && swapMin5.get(m5sz - 2).getClose() < swapMin5.get(m5sz - 3).getClose();
                        if (latestIsDown && continuousM5Down) {
                            log.info("[置换过滤-5分钟线] {} 换仓候选股5分钟线阴线且近3根连续下行，日内趋势偏弱，跳过本轮换仓（等待日内趋势好转）",
                                    swapCandCode);
                            // 将此候选从本轮候选池移除，不影响下次扫描
                            remainingCandidates.remove(swapCandCode);
                            continue;
                        }
                    }
                } catch (Exception e) {
                    log.debug("[置换过滤-5分钟线] {} 获取5分钟K线失败，跳过分钟线确认: {}", swapCandCode, e.getMessage());
                }
            }

            // ===== Step C：评分优势判断 =====
            int scoreDiff = bestNewScore - weakestScore;
            if (scoreDiff < SWAP_MIN_SCORE_ADVANTAGE) {
                log.info("[置换评估] 新股 {} (评分{} 趋势{}) vs 最弱持仓 {} (评分{})，优势仅{}分（需>={}），不置换",
                        bestNewSignal.getStockCode(), bestNewScore,
                        bestNewAnalysis.getTrend().getDescription(),
                        weakestPos.getStockCode(), weakestScore,
                        scoreDiff, SWAP_MIN_SCORE_ADVANTAGE);
                break; // 最强候选都不够优势，无需继续
            }

            // ===== Step D：执行置换（卖弱 → 买强） =====
            final String weakCode  = weakestPos.getStockCode();
            final String weakName  = weakestPos.getStockName();
            final int    wScore    = weakestScore;
            final double wProfit   = weakestProfitRate;

            log.warn("[仓位置换#{}/{}] ✦ 新股 {} {} (评分{} 趋势{} 强度{}) >> 最弱持仓 {} {} (评分{} 浮盈{}%)，评分差{}分",
                    swapCount + 1, SWAP_MAX_PER_ROUND,
                    bestNewSignal.getStockCode(), bestNewSignal.getStockName(),
                    bestNewScore, bestNewAnalysis.getTrend().getDescription(), bestNewSignal.getStrength(),
                    weakCode, weakName, wScore,
                    String.format("%.1f", wProfit * 100), scoreDiff);

            Position posSnap = portfolio.getPosition(weakCode);
            if (posSnap == null || posSnap.getAvailableQuantity() <= 0) {
                log.warn("[置换中止] {} 已无可用仓位，跳过本次置换", weakCode);
                remainingCandidates.remove(bestNewSignal.getStockCode());
                continue;
            }

            // 手动执行全仓卖出（不走 executeSell 的半仓逻辑）
            double sellPrice  = posSnap.getCurrentPrice();
            int    sellQty    = posSnap.getAvailableQuantity();
            double sellAmount = sellQty * sellPrice;
            String swapExchange = weakCode.startsWith("6") ? "SH" : "SZ";
            FeeCalculator.FeeDetail sellFee = feeCalculator.calculateSellFee(sellAmount, swapExchange);
            double swapPnl    = (sellPrice - posSnap.getAvgCost()) * sellQty - sellFee.total;
            cumulativeRealizedPnl += swapPnl;
            if (swapPnl > 0) winSellCount++;

            String swapSellReason = String.format(
                    "[仓位置换] 卖出评分较弱持仓(评分%d 趋势%s 浮盈%.1f%%)，换入更强标的 %s(评分%d 趋势%s)",
                    wScore, getHoldingTrendDesc(weakCode),
                    wProfit * 100,
                    bestNewSignal.getStockCode(), bestNewScore,
                    bestNewAnalysis.getTrend().getDescription());

            Order swapSellOrder = Order.builder()
                    .orderId(UUID.randomUUID().toString())
                    .stockCode(posSnap.getStockCode())
                    .stockName(posSnap.getStockName() != null ? posSnap.getStockName() : posSnap.getStockCode())
                    .orderType(Order.OrderType.SELL)
                    .status(Order.OrderStatus.FILLED)
                    .price(sellPrice).quantity(sellQty)
                    .filledPrice(sellPrice).filledQuantity(sellQty)
                    .amount(sellAmount)
                    .commission(sellFee.commission).stampTax(sellFee.stampTax)
                    .transferFee(sellFee.transferFee).totalFee(sellFee.total)
                    .createTime(LocalDateTime.now()).filledTime(LocalDateTime.now())
                    .strategyName("仓位置换")
                    .remark(swapSellReason)
                    .build();

            double swapSellPnlRate = posSnap.getAvgCost() > 0
                    ? (sellPrice - posSnap.getAvgCost()) / posSnap.getAvgCost() * 100 : 0;
            boolean sellOk = portfolio.executeSell(swapSellOrder);
            if (!sellOk) {
                log.warn("[置换中止] {} 卖出失败，跳过本次置换", weakCode);
                remainingCandidates.remove(bestNewSignal.getStockCode());
                continue;
            }
            totalSellCount++;
            printTradeReport("卖出(置换)", posSnap.getStockCode(), posSnap.getStockName(),
                    sellPrice, sellQty, sellAmount, sellFee.total, swapSellReason, null);
            saveTradeReportFile("卖出", swapSellOrder, swapSellReason, null, swapPnl, swapSellPnlRate);
            persistence.save(portfolio);
            pushService.sendSellSignal(username, posSnap.getStockCode(),
                    posSnap.getStockName() != null ? posSnap.getStockName() : posSnap.getStockCode(),
                    sellPrice, posSnap.getAvgCost(), sellQty, sellAmount, swapPnl, swapSellPnlRate,
                    swapSellReason,
                    (portfolio.getTotalAssets() - initialCapital) / initialCapital * 100,
                    buildPositionSummary());

            // 【修复6】记录换出时间（冷却期起点）
            swapCooldownMap.put(weakCode, System.currentTimeMillis());
            // 同时清理持仓评分缓存中的旧股
            holdingScoreCache.remove(weakCode);

            // 【修复2】从监控池中移除已换出的股票，防止下一轮再次买入
            watchlist = new ArrayList<>(watchlist);
            watchlist.remove(weakCode);
            log.debug("[置换] 已从监控池移除换出股: {}", weakCode);

            // 买入新股（置换时无完整K线数据，传null跳过ATR风控，依赖策略比例建仓）
            executeBuy(bestNewSignal, bestNewAnalysis, null);

            // 从候选池和阻塞信号中移除已买入的
            remainingCandidates.remove(bestNewSignal.getStockCode());
            blockedStrongBuySignals.remove(bestNewSignal.getStockCode());

            swapCount++;
        }

        if (swapCount > 0) {
            log.info("[置换完成] 本轮共完成 {} 次仓位置换", swapCount);
        }
    }

    /**
     * 获取持仓股票的趋势描述（用于置换日志）
     * 优先从 holdingScoreCache 对应的 screenResults 中获取，否则返回 "-"
     */
    private String getHoldingTrendDesc(String code) {
        // 从 screenResults 中查找
        for (StockScreener.ScreenResult sr : screenResults) {
            if (sr.stockCode.equals(code) && sr.analysis != null) {
                return sr.analysis.getTrend().getDescription();
            }
        }
        return "-";
    }

    /**
     * 满仓时专用：全市场扫描选出候选股，只将「未持仓的新候选」加入监控池，
     * 不改变持仓股在监控池中的位置，也不清空已持仓标的。
     * <p>
     * 与 refreshWatchlist 的区别：
     *   - refreshWatchlist：通用，无论满仓与否都刷新监控池（用于持仓不足时补仓选股）
     *   - refreshWatchlistForSwap：专为满仓置换设计，只增量添加非持仓候选，
     *     候选数量 = topN（而非 topN*3），选出最优的 topN 只供置换评估
     * </p>
     */
    private void refreshWatchlistForSwap() {
        try {
            // 扫出 topN 只候选（比持仓数量多即可），评分门槛与正常选股相同
            List<StockScreener.ScreenResult> results = screener.screenTopStocks(topN * 2, minScore);
            if (results.isEmpty()) {
                log.warn("[定频大盘扫描] 选股无结果（评分>{}），降低门槛重试...", minScore);
                results = screener.screenTopStocks(topN * 2, minScore - 10);
            }
            if (results.isEmpty()) {
                log.warn("[定频大盘扫描] 仍无结果，跳过本次置换候选刷新");
                return;
            }

            // 更新 screenResults（用于 tryPositionSwap 中评估持仓评分）
            screenResults = results;

            // ===== 【修复1】更新持仓股评分缓存 =====
            // 大盘扫描完成后，对持仓股也做一次实时技术分析，写入 holdingScoreCache，
            // 避免 tryPositionSwap 因持仓股不在 screenResults 里而误用默认50分。
            Set<String> holdingCodes = new HashSet<>(portfolio.getPositions().keySet());
            for (String holdCode : holdingCodes) {
                try {
                    LocalDate endDate = LocalDate.now();
                    // [P1-1 优化] K线范围同步缩短为180天日历
                    LocalDate startDate = endDate.minusDays(180);
                    List<StockBar> bars = dataProvider.getDailyBars(holdCode, startDate, endDate,
                            StockBar.AdjustType.FORWARD);
                    if (bars != null && bars.size() >= 60) {
                        StockBar rt = dataProvider.getRealTimeQuote(holdCode);
                        if (rt != null && rt.getClose() > 0) {
                            if (!bars.isEmpty() && bars.get(bars.size()-1).getDate() != null
                                    && bars.get(bars.size()-1).getDate().equals(rt.getDate())) {
                                bars.set(bars.size()-1, rt);
                            } else {
                                bars.add(rt);
                            }
                        }
                        String holdName = getStockName(holdCode);
                        AnalysisResult ar = analyzer.analyze(holdCode, holdName, bars);
                        if (ar != null) {
                            holdingScoreCache.put(holdCode, ar.getOverallScore());
                            log.debug("[持仓评分更新] {} {} 实时评分={}", holdCode, holdName, ar.getOverallScore());
                        }
                    }
                } catch (Exception ex) {
                    log.debug("[持仓评分更新] {} 失败: {}", holdCode, ex.getMessage());
                }
            }
            log.info("[定频大盘扫描] 持仓评分缓存已更新: {}", holdingScoreCache);

            // ===== 更新候选股连续达标天数（P1-2: 换仓冷却验证）=====
            // 规则：同一股票同一交易日只累加1次，防止单日多次扫描虚增天数
            // 未上榜的股票重置为0（今日不再是优质候选，连续性中断）
            String todayStr = LocalDate.now().toString();
            Set<String> currentCandidateCodes = new HashSet<>();
            for (StockScreener.ScreenResult r : results) {
                if (!holdingCodes.contains(r.stockCode)) {
                    currentCandidateCodes.add(r.stockCode);
                }
            }
            // 本次上榜：每只股票每个交易日只 +1（用 "stockCode_yyyy-MM-dd" 去重）
            for (String cCode : currentCandidateCodes) {
                String seenKey = cCode + "_" + todayStr;
                if (!candidateQualifyDaySeen.contains(seenKey)) {
                    candidateQualifyDaySeen.add(seenKey);
                    candidateQualifyDays.merge(cCode, 1, Integer::sum);
                    log.debug("[换仓验证] {} 今日首次上榜，累计达标{}天",
                            cCode, candidateQualifyDays.get(cCode));
                }
            }
            // 本次未上榜的：重置天数和seen记录（连续性中断）
            candidateQualifyDays.entrySet().removeIf(e -> {
                if (!currentCandidateCodes.contains(e.getKey())) {
                    // 同步清除 seen 记录
                    candidateQualifyDaySeen.removeIf(k -> k.startsWith(e.getKey() + "_"));
                    return true;
                }
                return false;
            });

            // 将非持仓的优质候选加入监控池，限制监控池总大小 = topN（持仓） + topN（候选）
            Set<String> newWatch = new LinkedHashSet<>(holdingCodes);
            int addedCount = 0;
            for (StockScreener.ScreenResult r : results) {
                if (holdingCodes.contains(r.stockCode)) continue; // 已持仓的不重复加
                if (newWatch.size() >= topN * 2) break;
                newWatch.add(r.stockCode);
                addedCount++;
            }
            watchlist = new ArrayList<>(newWatch);
            log.info("[定频大盘扫描] 监控池更新：持仓{}只 + 候选{}只 = 共{}只待扫描 {}",
                    holdingCodes.size(), addedCount, watchlist.size(), watchlist);
        } catch (Exception e) {
            log.error("[定频大盘扫描] 全市场选股失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 构建 fallback 保底监控池：当全市场选股无结果时使用。
     * <p>
     * 原逻辑硬编码贵州茅台(600519, 1500+元/股)，导致小资金账户（1万、5千元）
     * 根本买不起1手，有信号也无法成交。
     * <p>
     * 修复策略：根据账户可用资金动态选择价格合适的保底标的
     * <ul>
     *   <li>大资金（≥50万）：沪深蓝筹 贵州茅台/宁德时代/比亚迪（原逻辑）</li>
     *   <li>中等资金（5万~50万）：中盘蓝筹 工商银行/中国石油/兖矿能源/中国神华</li>
     *   <li>小资金（5千~5万）：低价蓝筹 中国银行/农业银行/中国石油/浦发银行</li>
     *   <li>超小资金（<5千）：极低价蓝筹 中国银行/农业银行（3~5元/股）</li>
     * </ul>
     */
    private List<String> buildFallbackWatchlist() {
        double availableCash = portfolio != null ? portfolio.getAvailableCash() : initialCapital;
        List<String> fallback;
        if (availableCash >= 500_000) {
            // 大资金：保留原有蓝筹池
            fallback = Arrays.asList("600519", "300750", "002594"); // 茅台/宁德/比亚迪
            log.warn("[Fallback监控池] 大资金账户，使用默认蓝筹池：{}", fallback);
        } else if (availableCash >= 50_000) {
            // 中等资金：中盘蓝筹，价格15~30元区间
            fallback = Arrays.asList("601398", "600028", "600188", "601088"); // 工商银行/中国石化/兖矿能源/中国神华
            log.warn("[Fallback监控池] 中等资金账户({}元)，使用中价蓝筹池：{}", (int) availableCash, fallback);
        } else if (availableCash >= 5_000) {
            // 小资金：低价蓝筹，价格3~8元区间（至少能买1手）
            fallback = Arrays.asList("601988", "601288", "601857", "600016"); // 中国银行/农业银行/中国石油/民生银行
            log.warn("[Fallback监控池] 小资金账户({}元)，使用低价蓝筹池：{}", (int) availableCash, fallback);
        } else {
            // 极小资金(<5000)：只选最低价蓝筹（3~5元）
            fallback = Arrays.asList("601288", "601988"); // 农业银行/中国银行（约3.5-4.5元/股）
            log.warn("[Fallback监控池] 超小资金账户({}元)，使用极低价蓝筹池：{}", (int) availableCash, fallback);
        }
        // 按 topN 截断，避免单持仓账户加入过多
        if (fallback.size() > topN) {
            fallback = fallback.subList(0, topN);
        }
        return fallback;
    }

    /**
     * 刷新选股池（重新全市场扫描）
     * <p>
     * 若配置 {@code screen.fundamental.enabled=true} 则使用技术面+基本面联合选股，
     * 同时应用 ROE 和营收增速硬过滤；否则退化为纯技术面选股。
     */
    public void refreshWatchlist() {
        // ===== 日内做T专用选股（IntradayTradingStrategy 模式）=====
        if (dayTradingScreener != null && strategy instanceof IntradayTradingStrategy) {
            refreshWatchlistForDayTrading();
            return;
        }

        // ===== 标准选股（分钟/技术面选股）=====
        try {
            SystemConfig cfg = SystemConfig.getInstance();
            boolean useFundamental  = cfg.isFundamentalScreenEnabled();
            double  techWeight      = cfg.getFundamentalTechWeight();
            double  roeMin          = cfg.getFundamentalRoeMin();
            double  revenueYoyMin   = cfg.getFundamentalRevenueYoyMin();

            // [资金过滤] 根据账户可用资金计算最大可买股价，过滤买不起的高价股
            // 逻辑：至少能买1手（100股），若 availableCash < price*100 则买不起
            // maxAffordablePrice = availableCash / 100；大资金账户（>50万）不限制（0表示不过滤）
            double availableCash = portfolio.getAvailableCash();
            double maxAffordablePrice = 0; // 默认不过滤（大资金账户）
            if (availableCash < 500_000) {
                // 小资金账户：过滤买不起1手的股票，留出20%容错空间避免边界误杀
                maxAffordablePrice = availableCash * 0.8 / 100;
                log.info("[选股资金过滤] 可用资金={}元，股价上限={}元/股（过滤至少需{}元才能买1手的标的）",
                        String.format("%.0f", availableCash),
                        String.format("%.0f", maxAffordablePrice),
                        String.format("%.0f", maxAffordablePrice * 100));
            }

            log.info("开始全市场选股（{}）...",
                    useFundamental ? String.format("技术+基本面联合，ROE>%.0f%%，营收增速>%.0f%%", roeMin, revenueYoyMin)
                                   : "纯技术面");

            List<StockScreener.ScreenResult> results = useFundamental
                    ? screener.screenWithFundamental(topN * 3, minScore, true, techWeight, roeMin, revenueYoyMin, maxAffordablePrice)
                    : screener.screenTopStocks(topN * 3, minScore, maxAffordablePrice);

            if (results.isEmpty()) {
                log.warn("选股无结果（评分>{}），降低门槛重试...", minScore);
                results = useFundamental
                        ? screener.screenWithFundamental(topN * 3, minScore - 10, true, techWeight, roeMin, revenueYoyMin, maxAffordablePrice)
                        : screener.screenTopStocks(topN * 3, minScore - 10, maxAffordablePrice);
            }

            if (!results.isEmpty()) {
                screenResults = results;
                // 只将未持仓的新标的加入监控池，已持仓的继续保留
                Set<String> newWatch = new LinkedHashSet<>(portfolio.getPositions().keySet());
                for (StockScreener.ScreenResult r : results) {
                    if (newWatch.size() >= topN * 2) break;
                    newWatch.add(r.stockCode);
                }
                watchlist = new ArrayList<>(newWatch);
                log.info("监控池更新（共{}只）：{}", watchlist.size(), watchlist);
            }
        } catch (Exception e) {
            log.error("刷新选股池失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 日内做T专用选股：使用 DayTradingStockScreener 全市场选出最优1支做T标的
     * <p>
     * 选股规则：
     * 1. 若当前有持仓，则继续监控已持仓股票（做T持股不换）
     *    - 仅当持仓股今日跌幅 <= -5% 或技术面明显破位时才换股
     * 2. 若无持仓，全市场选出做T综合分最高的1支股票
     */
    private void refreshWatchlistForDayTrading() {
        try {
            log.info("[日内做T选股] 开始扫描，寻找最优做T标的...");

            // 若已有持仓，继续持有（当日做T不轻易换标的）
            if (!portfolio.getPositions().isEmpty()) {
                String holdCode = portfolio.getPositions().keySet().iterator().next();
                if (!watchlist.contains(holdCode)) {
                    watchlist = new ArrayList<>(portfolio.getPositions().keySet());
                }
                log.info("[日内做T选股] 当前持仓 {}，继续监控持仓标的（不换股）", holdCode);
                return;
            }

            // 无持仓：全市场扫描最优做T标的
            DayTradingStockScreener.DayTradingCandidate best =
                    dayTradingScreener.selectBestForDayTrading();

            if (best != null) {
                watchlist = new ArrayList<>();
                watchlist.add(best.stockCode);
                log.info("[日内做T选股] 选出最优标的: {} {} | 做T综合分={} | 日内振幅={}% ATR={}%",
                        best.stockCode, best.stockName, best.dayTradingScore,
                        String.format("%.2f", best.avgSwingPct * 100),
                        String.format("%.2f", best.atrPct * 100));
                // 微信推送选股结果
                pushService.sendMessage("[📊日内做T选股] " + username,
                        String.format("今日做T标的：%s %s\n现价=%.2f 日内振幅=%.2f%% ATR=%.2f%%\n做T综合分=%d",
                                best.stockCode, best.stockName, best.currentPrice,
                                best.avgSwingPct * 100, best.atrPct * 100, best.dayTradingScore));
            } else {
                log.warn("[日内做T选股] 未找到符合标准的做T标的，保持当前监控池");
            }
        } catch (Exception e) {
            log.error("[日内做T选股] 选股失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 初始化/恢复账户
     */
    private void initPortfolio() {
        Portfolio loaded = persistence.load(accountId);
        if (loaded != null) {
            this.portfolio = loaded;
            log.info("已恢复历史账户：资金={} 持仓={}只",
                    String.format("%.2f", portfolio.getAvailableCash()),
                    portfolio.getPositions().size());
            // [Bug Fix] 重启后从历史 closedPositions 重建内存统计计数器，
            // 避免 cumulativeRealizedPnl / winSellCount / totalSellCount 从 0 开始
            rebuildStatsFromHistory(portfolio);
        } else {
            this.portfolio = new Portfolio(accountId, "自动交易账户",
                    initialCapital, Portfolio.AccountMode.SIMULATION);
            log.info("创建新账户：初始资金 {} 元", String.format("%.2f", initialCapital));
            persistence.save(portfolio);
        }

        // ===== [P3-1 优化] 重启后恢复大亏冷却期状态 =====
        // 从数据库读取持久化的冷却期触发日期，防止重启后冷却期约束失效
        LocalDate savedCooldownDate = persistence.loadLargelossCooldownDate(accountId);
        if (savedCooldownDate != null) {
            LocalDate cooldownNextDay = savedCooldownDate.plusDays(1);
            if (LocalDate.now().isBefore(cooldownNextDay)) {
                // 冷却期仍有效（今天是触发日或次日），恢复内存状态
                largeLossCooldownTriggerDate = savedCooldownDate;
                log.warn("[P3-1大亏冷却] 重启后恢复冷却期状态：触发日={}，冷却期有效至={}", savedCooldownDate, cooldownNextDay);
            } else {
                // 冷却期已过，清除数据库记录
                persistence.saveLargelossCooldownDate(accountId, null);
                log.info("[P3-1大亏冷却] 重启后检测到冷却期已过期（触发日={}），已自动清除", savedCooldownDate);
            }
        }
    }

    /**
     * 从已恢复的 closedPositions 和 orderHistory 重建内存统计计数器。
     * <p>
     * 背景：cumulativeRealizedPnl / winSellCount / totalSellCount / totalBuyCount
     * 均为纯内存字段，程序重启后从 0 开始。需要从持久化数据中恢复，
     * 否则"累计已实现盈亏"和"历史胜率"在重启后会显示错误（只统计本次运行内的交易）。
     * <p>
     * 恢复策略：
     * - cumulativeRealizedPnl → 直接使用 portfolio.getRealizedPnl()（AccountPersistence 已从 closedPositions 累加）
     * - winSellCount / totalSellCount → 遍历 closedPositions 统计（更准确，不依赖 orderHistory 500条限制）
     * - totalBuyCount → 遍历 orderHistory 中的 BUY 记录（近似值，受 500 条限制影响，仅供参考）
     */
    private void rebuildStatsFromHistory(Portfolio portfolio) {
        // 1. 从 portfolio.realizedPnl 恢复累计已实现盈亏
        //    （AccountPersistence.loadClosedPositions 已正确计算: sum of cp.realizedPnl）
        this.cumulativeRealizedPnl = portfolio.getRealizedPnl();

        // 2. 从 closedPositions 恢复胜率统计（比 orderHistory 更准确，不受500条限制）
        java.util.List<com.stocktrader.model.ClosedPosition> closed = portfolio.getClosedPositions();
        int winCount = 0;
        for (com.stocktrader.model.ClosedPosition cp : closed) {
            if (cp.getRealizedPnl() > 0) winCount++;
        }
        this.winSellCount   = winCount;
        this.totalSellCount = closed.size();

        // 3. 从 orderHistory 恢复买入次数（近似值，受500条加载限制影响）
        int buyCount = 0;
        for (com.stocktrader.model.Order o : portfolio.getOrderHistory()) {
            if (o.getOrderType() == com.stocktrader.model.Order.OrderType.BUY) buyCount++;
        }
        this.totalBuyCount = buyCount;

        log.info("[统计恢复] 累计已实现盈亏={} 历史卖出={}次 历史胜率={}% 历史买入={}次（近似）",
                String.format("%+.2f", this.cumulativeRealizedPnl),
                this.totalSellCount,
                this.totalSellCount > 0
                        ? String.format("%.1f", (double) this.winSellCount / this.totalSellCount * 100)
                        : "0.0",
                this.totalBuyCount);
    }

    // =================== 打印方法 ===================

    private void printHeader() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║          📈 全自动A股智能模拟交易系统 v2.0                    ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  初始资金: %,-15.2f 元                              ║%n", initialCapital);
        System.out.printf("║  选股Top N: %-3d  最低评分: %-3d  扫描间隔: %-3d 分钟           ║%n",
                topN, minScore, scanIntervalMin);
        System.out.println("║  策略: 综合多因子策略（止损7% / 止盈15%）                       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }

    private void printWatchlist() {
        System.out.println("\n【当前监控股票池】");
        System.out.println("──────────────────────────────────────────────────────────────");
        System.out.printf("  %-8s %-10s %7s %7s %7s %8s %6s  %s%n",
                "代码", "名称", "现价", "涨跌幅", "换手率", "市值(亿)", "评分", "建议");
        System.out.println("──────────────────────────────────────────────────────────────");
        for (StockScreener.ScreenResult r : screenResults) {
            if (watchlist.contains(r.stockCode)) {
                System.out.printf("  %-8s %-10s %7.2f %+6.2f%% %6.2f%% %8.1f %6d  %s%n",
                        r.stockCode, r.stockName, r.currentPrice, r.changePercent,
                        r.turnoverRate, r.totalMarketCap, r.techScore, r.recommendation);
            }
        }
        System.out.println("──────────────────────────────────────────────────────────────\n");
    }

    /**
     * 打印单笔交易报告
     */
    private void printTradeReport(String action, String code, String name,
                                   double price, int quantity, double amount, double fee,
                                   String reason, AnalysisResult analysis) {
        String sep = "══════════════════════════════════════════════════════";
        System.out.println("\n" + sep);
        System.out.printf("  🔔 【%s成交】%s %s%n", action, code, name != null ? name : "");
        System.out.printf("  时间:   %s%n", LocalDateTime.now().format(DT_FMT));
        System.out.printf("  价格:   %.2f 元%n", price);
        System.out.printf("  数量:   %d 股（%d手）%n", quantity, quantity / 100);
        System.out.printf("  金额:   %,.2f 元%n", amount);
        System.out.printf("  手续费: %.2f 元%n", fee);
        System.out.printf("  原因:   %s%n", reason);
        if (analysis != null) {
            System.out.printf("  评分:   %d/100  趋势:%s%n",
                    analysis.getOverallScore(), analysis.getTrend().getDescription());
        }
        System.out.println(sep);
    }

    /**
     * 打印单笔盈亏报告
     */
    private void printPnlReport(String code, String name, double cost, double price,
                                  int quantity, double pnl) {
        double pnlRate = cost > 0 ? (price - cost) / cost * 100 : 0;
        double totalReturn = (portfolio.getTotalAssets() - initialCapital) / initialCapital * 100;

        System.out.println("──────────────────────────────────────────────────────────────");
        System.out.printf("  📊 【收益报告】%s %s%n", code, name != null ? name : "");
        System.out.printf("  买入成本: %.2f 元/股%n", cost);
        System.out.printf("  卖出价格: %.2f 元/股%n", price);
        System.out.printf("  交易数量: %d 股%n", quantity);
        System.out.printf("  本次盈亏: %+,.2f 元  (%+.2f%%)%n", pnl, pnlRate);
        System.out.println("──────────────────────────────────────────────────────────────");
        System.out.printf("  📈 【账户总览】%n");
        System.out.printf("  初始资金: %,.2f 元%n", initialCapital);
        System.out.printf("  当前资产: %,.2f 元%n", portfolio.getTotalAssets());
        System.out.printf("  累计已实现盈亏: %+,.2f 元%n", cumulativeRealizedPnl);
        System.out.printf("  账户总收益率:   %+.2f%%%n", totalReturn);
        System.out.printf("  历史胜率: %.1f%% (%d/%d)%n",
                totalSellCount > 0 ? (double) winSellCount / totalSellCount * 100 : 0,
                winSellCount, totalSellCount);
        System.out.println("──────────────────────────────────────────────────────────────\n");
    }

    /**
     * 打印账户实时摘要（每次扫描后）
     */
    public void printAccountSummary() {
        double totalAssets = portfolio.getTotalAssets();
        double totalProfit = totalAssets - initialCapital;
        double totalReturn = totalAssets > 0 ? totalProfit / initialCapital * 100 : 0;

        System.out.println("\n┌─────────────────── 账户状态 ───────────────────────────────┐");
        System.out.printf("│ 时间: %-52s│%n", LocalDateTime.now().format(DT_FMT));
        System.out.printf("│ 总资产: %,-15.2f  可用资金: %,-15.2f        │%n",
                totalAssets, portfolio.getAvailableCash());
        System.out.printf("│ 持仓市值: %,-13.2f  总收益: %+,-13.2f (%.2f%%) │%n",
                portfolio.getTotalPositionValue(), totalProfit, totalReturn);

        if (!portfolio.getPositions().isEmpty()) {
            System.out.println("│ 持仓明细:                                                    │");
            portfolio.getPositions().forEach((code, pos) -> {
                double posReturn = pos.getAvgCost() > 0
                        ? (pos.getCurrentPrice() - pos.getAvgCost()) / pos.getAvgCost() * 100 : 0;
                // 展示时兜底补全名称（历史遗留名称为空时自动填充）
                String displayName = (pos.getStockName() != null && !pos.getStockName().isEmpty())
                        ? pos.getStockName() : getStockName(code);
                if (pos.getStockName() == null || pos.getStockName().isEmpty()) {
                    pos.setStockName(displayName);  // 同步更新持仓对象
                }
                System.out.printf("│   %-8s %-8s %5d股 成本%7.2f 现%7.2f 盈亏%+6.2f%%       │%n",
                        code, displayName, pos.getQuantity(),
                        pos.getAvgCost(), pos.getCurrentPrice(), posReturn);
            });
        } else {
            System.out.println("│ 当前空仓                                                     │");
        }
        System.out.println("└─────────────────────────────────────────────────────────────┘");
    }

    /**
     * 最终汇总报告（退出时打印）
     */
    public void printFinalReport() {
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║                    最终交易汇总报告                           ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        double totalAssets = portfolio.getTotalAssets();
        double totalProfit = totalAssets - initialCapital;
        double totalReturn = totalProfit / initialCapital * 100;
        System.out.printf("║  初始资金:      %,-20.2f 元                  ║%n", initialCapital);
        System.out.printf("║  最终资产:      %,-20.2f 元                  ║%n", totalAssets);
        System.out.printf("║  总盈亏:        %+,-20.2f 元                  ║%n", totalProfit);
        System.out.printf("║  总收益率:      %+.2f%%%-30s  ║%n", totalReturn, "");
        System.out.printf("║  已实现盈亏:    %+,-20.2f 元                  ║%n", cumulativeRealizedPnl);
        System.out.printf("║  买入次数:      %-20d                       ║%n", totalBuyCount);
        System.out.printf("║  卖出次数:      %-20d                       ║%n", totalSellCount);
        System.out.printf("║  历史胜率:      %.1f%% (%d/%d)%-25s  ║%n",
                totalSellCount > 0 ? (double) winSellCount / totalSellCount * 100 : 0,
                winSellCount, totalSellCount, "");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }

    private String getStockName(String code) {
        // 1. 优先从选股结果缓存中查（stockName 有效且非空）
        for (StockScreener.ScreenResult r : screenResults) {
            if (r.stockCode.equals(code) && r.stockName != null && !r.stockName.isEmpty()) {
                return r.stockName;
            }
        }
        // 2. 次选：从持仓中取（上次买入时写入的名称）
        Position pos = portfolio.getPosition(code);
        if (pos != null && pos.getStockName() != null && !pos.getStockName().isEmpty()) {
            return pos.getStockName();
        }
        // 3. 兜底：实时拉一次新浪行情，从 parseSinaQuote 中取名称（已在 StockBar.stockName 中填充）
        try {
            StockBar rt = dataProvider.getRealTimeQuote(code);
            if (rt != null && rt.getStockName() != null && !rt.getStockName().isEmpty()) {
                return rt.getStockName();
            }
        } catch (Exception ignore) {
            // 网络异常时静默降级
        }
        // 4. 最终降级：直接返回股票代码（总不会空）
        return code;
    }

    // =================== 报告文件保存方法 ===================

    /**
     * 保存单笔交易报告到 trade-reports/trades/ 目录
     * 文件名：trade_买入_000001_20260303_103012.txt
     */
    private void saveTradeReportFile(String action, Order order, String reason,
                                      AnalysisResult analysis, double pnl, double pnlRate) {
        LocalDateTime now = LocalDateTime.now();
        String ts = now.format(FILE_TS_FMT);
        String filename = String.format("%s/trades/trade_%s_%s_%s.txt",
                reportDir, action, order.getStockCode(), ts);
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename, false))) {
            pw.println("══════════════════════════════════════════════════════");
            pw.printf("  【%s成交报告】%n", action);
            pw.println("══════════════════════════════════════════════════════");
            pw.printf("  股票代码: %s%n", order.getStockCode());
            pw.printf("  股票名称: %s%n", order.getStockName() != null ? order.getStockName() : order.getStockCode());
            pw.printf("  交易类型: %s%n", action);
            pw.printf("  成交时间: %s%n", now.format(DT_FMT));
            pw.printf("  成交价格: %.2f 元%n", order.getFilledPrice());
            pw.printf("  成交数量: %d 股（%d 手）%n", order.getFilledQuantity(), order.getFilledQuantity() / 100);
            pw.printf("  成交金额: %,.2f 元%n", order.getAmount());
            pw.printf("  手续费用: %.2f 元（佣金%.2f + 印花税%.2f + 过户费%.2f）%n",
                    order.getTotalFee(), order.getCommission(), order.getStampTax(), order.getTransferFee());
            pw.printf("  触发原因: %s%n", reason != null ? reason : "");
            pw.printf("  策略名称: %s%n", order.getStrategyName() != null ? order.getStrategyName() : "");
            if (analysis != null) {
                pw.printf("  技术评分: %d/100%n", analysis.getOverallScore());
                pw.printf("  趋势判断: %s%n", analysis.getTrend().getDescription());
            }
            if ("卖出".equals(action)) {
                pw.println("──────────────────────────────────────────────────────");
                pw.printf("  本次盈亏: %+,.2f 元  (%+.2f%%)%n", pnl, pnlRate);
                pw.println("──────────────────────────────────────────────────────");
                pw.printf("  初始资金: %,.2f 元%n", initialCapital);
                pw.printf("  当前资产: %,.2f 元%n", portfolio.getTotalAssets());
                pw.printf("  累计盈亏: %+,.2f 元%n", cumulativeRealizedPnl);
                pw.printf("  账户收益率: %+.2f%%%n",
                        (portfolio.getTotalAssets() - initialCapital) / initialCapital * 100);
                pw.printf("  历史胜率: %.1f%% (%d/%d)%n",
                        totalSellCount > 0 ? (double) winSellCount / totalSellCount * 100 : 0,
                        winSellCount, totalSellCount);
            }
            pw.println("══════════════════════════════════════════════════════");
            log.info("交易报告已保存: {}", filename);
        } catch (IOException e) {
            log.error("保存交易报告失败: {}", filename, e);
        }
        // 同时追加到当日汇总流水文件
        appendToDailyTradeLog(action, order, pnl, pnlRate);
    }

    /**
     * 追加一条记录到当日交易流水文件 trade-reports/daily/trades_yyyyMMdd.txt
     */
    private void appendToDailyTradeLog(String action, Order order, double pnl, double pnlRate) {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String filename = String.format("%s/daily/trades_%s.txt", reportDir, today);
        boolean isNew = !new File(filename).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename, true))) {
            if (isNew) {
                pw.printf("═══════════════════════════════════════════════════════════════════%n");
                pw.printf("   %s  交易流水日志%n", LocalDate.now().format(DATE_FMT));
                pw.printf("═══════════════════════════════════════════════════════════════════%n");
                pw.printf("  %-19s %-4s %-8s %-8s %8s %7s %8s %12s%n",
                        "时间", "方向", "代码", "名称", "价格", "数量(股)", "金额", "盈亏");
                pw.println("───────────────────────────────────────────────────────────────────");
            }
            pw.printf("  %-19s %-4s %-8s %-8s %8.2f %7d %,8.0f  %s%n",
                    LocalDateTime.now().format(DT_FMT),
                    action,
                    order.getStockCode(),
                    order.getStockName() != null ? order.getStockName() : order.getStockCode(),
                    order.getFilledPrice(),
                    order.getFilledQuantity(),
                    order.getAmount(),
                    "卖出".equals(action) ? String.format("%+,.2f(%+.2f%%)", pnl, pnlRate) : "--");
        } catch (IOException e) {
            log.error("追加日流水失败: {}", filename, e);
        }
    }

    /**
     * 生成当日汇总报告（收盘后调用）
     * 保存到 trade-reports/daily/summary_yyyyMMdd.txt
     */
    public void saveDailySummaryReport() {
        String today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String filename = String.format("%s/daily/summary_%s.txt", reportDir, today);
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename, false))) {
            double totalAssets = portfolio.getTotalAssets();
            double totalProfit = totalAssets - initialCapital;
            double totalReturn = initialCapital > 0 ? totalProfit / initialCapital * 100 : 0;
            double dailyProfit = getDailyProfit();
            double dailyReturn = dailyStartAssets > 0 ? dailyProfit / dailyStartAssets * 100 : 0;
            double winRate = totalSellCount > 0 ? (double) winSellCount / totalSellCount * 100 : 0;

            // ===== 计算盈亏比（Profit Factor） =====
            // 盈亏比 = 平均盈利幅度 / 平均亏损幅度（绝对值）
            // 基于 closedPositions 中的 realizedPnlRate 计算
            List<com.stocktrader.model.ClosedPosition> closed = portfolio.getClosedPositions();
            double sumWinRate  = 0, sumLossRate = 0;
            int    winCnt = 0, lossCnt = 0;
            for (com.stocktrader.model.ClosedPosition cp : closed) {
                if (cp.getRealizedPnlRate() > 0) { sumWinRate  += cp.getRealizedPnlRate(); winCnt++;  }
                else if (cp.getRealizedPnlRate() < 0) { sumLossRate += Math.abs(cp.getRealizedPnlRate()); lossCnt++; }
            }
            double avgWinRate  = winCnt  > 0 ? sumWinRate  / winCnt  : 0;
            double avgLossRate = lossCnt > 0 ? sumLossRate / lossCnt : 0;
            double profitFactor = avgLossRate > 0 ? avgWinRate / avgLossRate : 0; // 盈亏比

            // ===== 近似夏普比率（基于已平仓交易收益率序列）=====
            // 用已平仓的 realizedPnlRate 序列代替日收益序列（近似），无风险利率取 3%/年 ≈ 0.012%/日
            // 注意：此为近似计算，不是严格日度夏普；有意义性需至少 30 笔以上平仓记录
            double sharpeRatio = 0;
            if (closed.size() >= 5) {
                double[] pnlRates = closed.stream().mapToDouble(com.stocktrader.model.ClosedPosition::getRealizedPnlRate).toArray();
                double mean = 0;
                for (double r : pnlRates) mean += r;
                mean /= pnlRates.length;
                double variance = 0;
                for (double r : pnlRates) variance += (r - mean) * (r - mean);
                double stddev = Math.sqrt(variance / pnlRates.length);
                double riskFreeRate = 0.012; // 约 3%/年 ÷ 250交易日 ≈ 0.012% 每笔
                sharpeRatio = stddev > 0 ? (mean - riskFreeRate) / stddev : 0;
            }

            pw.println("╔══════════════════════════════════════════════════════════════╗");
            pw.printf("║       %s  当日交易汇总报告                          ║%n",
                    LocalDate.now().format(DATE_FMT));
            pw.println("╠══════════════════════════════════════════════════════════════╣");
            pw.printf("║  初始总资金: %,-20.2f 元                  ║%n", initialCapital);
            pw.printf("║  当前总资产: %,-20.2f 元                  ║%n", totalAssets);
            pw.printf("║  当日盈亏:   %+,-20.2f 元（%+.2f%%）         ║%n", dailyProfit, dailyReturn);
            pw.printf("║  累计总盈亏: %+,-20.2f 元（%+.2f%%）         ║%n", totalProfit, totalReturn);
            pw.printf("║  累计已实现盈亏: %+,-17.2f 元                  ║%n", cumulativeRealizedPnl);
            pw.printf("║  今日买入次数: %-19d                       ║%n", totalBuyCount);
            pw.printf("║  今日卖出次数: %-19d                       ║%n", totalSellCount);
            pw.printf("║  历史胜率:   %.1f%% (%d/%d)%-24s  ║%n",
                    winRate, winSellCount, totalSellCount, "");
            pw.println("╠══════════════════════════════════════════════════════════════╣");
            pw.println("║  📊 风险绩效指标:                                              ║");
            pw.printf("║  最大回撤:   %+,-16.2f 元  (-%4.2f%%)               ║%n",
                    maxDrawdownAbs, maxDrawdownPct);
            pw.printf("║  盈亏比:     %-44s║%n",
                    profitFactor > 0
                        ? String.format("%.2f  (均盈%.2f%% / 均亏%.2f%%，共%d笔平仓)", profitFactor, avgWinRate, avgLossRate, closed.size())
                        : "数据不足（平仓记录 < 1笔）");
            pw.printf("║  夏普比率:   %-44s║%n",
                    closed.size() >= 5
                        ? String.format("%.3f  (基于%d笔平仓收益率序列，近似计算)", sharpeRatio, closed.size())
                        : "数据不足（需 >= 5 笔平仓记录）");
            pw.println("╠══════════════════════════════════════════════════════════════╣");
            pw.println("║  当前持仓:                                                    ║");
            if (portfolio.getPositions().isEmpty()) {
                pw.println("║    当前空仓                                                   ║");
            } else {
                portfolio.getPositions().forEach((code, pos) -> {
                    double posReturn = pos.getAvgCost() > 0
                            ? (pos.getCurrentPrice() - pos.getAvgCost()) / pos.getAvgCost() * 100 : 0;
                    pw.printf("║    %-8s %-8s %5d股 成本%7.2f 现%7.2f %+.2f%%         ║%n",
                            code, pos.getStockName(), pos.getQuantity(),
                            pos.getAvgCost(), pos.getCurrentPrice(), posReturn);
                });
            }
            pw.println("╚══════════════════════════════════════════════════════════════╝");
            log.info("当日汇总报告已保存: {}", filename);
        } catch (IOException e) {
            log.error("保存当日汇总报告失败: {}", filename, e);
        }
    }

    /**
     * 生成最终总结报告（程序退出时调用）
     * 保存到 trade-reports/final_summary_yyyyMMdd_HHmmss.txt
     */
    public void saveFinalSummaryReport() {
        LocalDateTime now = LocalDateTime.now();
        String filename = String.format("%s/final_summary_%s.txt", reportDir, now.format(FILE_TS_FMT));
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename, false))) {
            double totalAssets = portfolio.getTotalAssets();
            double totalProfit = totalAssets - initialCapital;
            double totalReturn = totalProfit / initialCapital * 100;

            pw.println("╔══════════════════════════════════════════════════════════════╗");
            pw.println("║              📊 全自动模拟交易 最终总结报告                    ║");
            pw.println("╠══════════════════════════════════════════════════════════════╣");
            pw.printf("║  报告时间: %-49s║%n", now.format(DT_FMT));
            pw.println("╠══════════════════════════════════════════════════════════════╣");
            pw.printf("║  初始资金:      %,-20.2f 元                  ║%n", initialCapital);
            pw.printf("║  最终资产:      %,-20.2f 元                  ║%n", totalAssets);
            pw.printf("║  总盈亏:        %+,-20.2f 元                  ║%n", totalProfit);
            pw.printf("║  总收益率:      %+.2f%%%-30s  ║%n", totalReturn, "");
            pw.printf("║  累计已实现盈亏: %+,-19.2f 元                  ║%n", cumulativeRealizedPnl);
            pw.printf("║  总买入次数:    %-20d                       ║%n", totalBuyCount);
            pw.printf("║  总卖出次数:    %-20d                       ║%n", totalSellCount);
            pw.printf("║  历史胜率:      %.1f%% (%d/%d)%-24s  ║%n",
                    totalSellCount > 0 ? (double) winSellCount / totalSellCount * 100 : 0,
                    winSellCount, totalSellCount, "");
            pw.println("╠══════════════════════════════════════════════════════════════╣");
            pw.println("║  全部历史订单:                                                ║");
            pw.printf("║  %-19s %-4s %-8s %-8s %8s %7s %10s ║%n",
                    "时间", "方向", "代码", "名称", "价格", "数量", "金额");
            pw.println("╠══════════════════════════════════════════════════════════════╣");
            for (Order o : portfolio.getOrderHistory()) {
                String t = o.getFilledTime() != null ? o.getFilledTime().format(DT_FMT) : "--";
                pw.printf("║  %-19s %-4s %-8s %-8s %8.2f %7d %,10.0f ║%n",
                        t, o.getOrderType().name().equals("BUY") ? "买入" : "卖出",
                        o.getStockCode(),
                        o.getStockName() != null ? o.getStockName() : o.getStockCode(),
                        o.getFilledPrice(), o.getFilledQuantity(), o.getAmount());
            }
            pw.println("╚══════════════════════════════════════════════════════════════╝");
            log.info("最终总结报告已保存: {}", filename);
        } catch (IOException e) {
            log.error("保存最终报告失败: {}", filename, e);
        }
    }

    /**
     * 发送每日汇总微信推送
     */
    private void sendDailySummaryPush() {
        if (!pushService.isEnabled()) return;
        // 确保基准已设置（收盘推送时 runOneScan 可能从未执行过，如重启时段在盘后）
        refreshDailyBaseline();
        double totalAssets = portfolio.getTotalAssets();
        // 累计总盈亏（从建仓以来）
        double totalProfit = totalAssets - initialCapital;
        double totalReturn = initialCapital > 0 ? totalProfit / initialCapital * 100 : 0;
        // 当日盈亏
        double dailyProfit = getDailyProfit();
        double dailyReturn = dailyStartAssets > 0 ? dailyProfit / dailyStartAssets * 100 : 0;

        StringBuilder posStr = new StringBuilder();
        if (portfolio.getPositions().isEmpty()) {
            posStr.append("当前空仓");
        } else {
            portfolio.getPositions().forEach((code, pos) -> {
                double posReturn = pos.getAvgCost() > 0
                        ? (pos.getCurrentPrice() - pos.getAvgCost()) / pos.getAvgCost() * 100 : 0;
                posStr.append(String.format("- **%s %s** %d股 成本%.2f 现价%.2f **%+.2f%%**\n",
                        code, pos.getStockName() != null ? pos.getStockName() : code,
                        pos.getQuantity(), pos.getAvgCost(), pos.getCurrentPrice(), posReturn));
            });
        }
        pushService.sendDailySummary(username, totalAssets, initialCapital,
                totalProfit, totalReturn, dailyProfit, dailyReturn, posStr.toString());
    }

    /**
     * 生成当前持仓摘要字符串（用于微信推送）
     */
    private String buildPositionSummary() {
        if (portfolio.getPositions().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        portfolio.getPositions().forEach((code, pos) -> {
            double posReturn = pos.getAvgCost() > 0
                    ? (pos.getCurrentPrice() - pos.getAvgCost()) / pos.getAvgCost() * 100 : 0;
            double posMarketValue = pos.getQuantity() * pos.getCurrentPrice();
            sb.append(String.format("- **%s %s** %d股 成本%.2f 现价%.2f 市值%,.0f元 **%+.2f%%**\n",
                    code, pos.getStockName() != null ? pos.getStockName() : code,
                    pos.getQuantity(), pos.getAvgCost(), pos.getCurrentPrice(), posMarketValue, posReturn));
        });
        return sb.toString();
    }

    /**
     * 获取本机局域网 IPv4 地址（非回环地址），用于生成微信推送中的可点击链接。
     */
    private static String getLocalIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取本机IP失败: {}", e.getMessage());
        }
        return "127.0.0.1";
    }

    // getter，供外部调用
    public Portfolio getPortfolio() { return portfolio; }
    public List<String> getWatchlist() { return watchlist; }
    public List<StockScreener.ScreenResult> getScreenResults() { return screenResults; }
    public TradeCallbackServer getCallbackServer() { return callbackServer; }
}


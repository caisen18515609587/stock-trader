package com.stocktrader.strategy;

import com.stocktrader.analysis.AtrStopLoss;
import com.stocktrader.analysis.TechnicalIndicator;
import com.stocktrader.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * 日内做T策略（高频秒级扫描版）
 * <p>
 * 策略目标：针对已选定的1支做T标的，每20秒扫描实时行情，
 * 在日内高点卖出（减仓/清仓），在日内低点买入（建仓/加仓），
 * 通过反复的日内高卖低买来累积收益。
 * <p>
 * ===== 核心逻辑 =====
 * <p>
 * 【卖出条件（高点识别）】优先级从高到低：
 *   1. 强制止损：当前价 <= 成本价*(1-stopLoss%)，立即全仓止损出局
 *   2. 清仓止盈：盈利 >= takeProfitFull%，全仓了结
 *   3. 日内高点卖出（做T减仓）：
 *      - 当前价 >= 今日最高价 * 0.98（接近今日高点）
 *      - 且 5分钟线连续下行（检测高点反转）
 *      - 且盈利 >= doTProfitMin%（有最低收益保障）
 *      - 卖出50%仓位（减仓做T）
 *   4. 尾盘强制处理：14:45后若有浮盈则清仓，浮亏则持有（T+1次日低风险做T）
 * <p>
 * 【买入条件（低点识别）】：
 *   1. 日内低点回调买入：
 *      - 当前价 <= 今日最低价 * 1.02（接近今日低点）
 *      - 且 5分钟线连续上行（检测低点反转/企稳）
 *      - 且当前价 >= 昨收 * 0.97（不追跌破位股）
 *      - 且日线技术面评分 >= 55（基本面支撑）
 *   2. 早盘低吸：9:35-10:00，若开盘后回调2%以上且企稳，买入
 * <p>
 * 【仓位管理】：
 *   - 初始建仓：80%仓位（集中持仓，小资金做T）
 *   - 做T减仓：卖出50%后若低点回调再买入，形成"卖高买低"循环
 *   - 最多持有1支股票（单标的专注做T）
 * <p>
 * 注意：此策略依赖实时行情（分钟K线），需配合秒级扫描调度器使用。
 * 与 DayTradingStrategy 的区别：
 *   - DayTradingStrategy：基于日线K线判断，偏日线级别信号，较低频
 *   - IntradayTradingStrategy：基于分钟线实时价格判断，高频日内操作
 */
public class IntradayTradingStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(IntradayTradingStrategy.class);

    // ===== 参数 =====
    private final double stopLossPercent;       // 止损比例（如0.03=3%）
    private final double takeProfitFull;        // 清仓止盈（如0.08=8%）
    private final double takeProfitHalf;        // 减仓止盈（如0.05=5%）
    private final double doTProfitMin;          // 做T减仓最低收益（如0.015=1.5%）
    private final double maxPositionRatio;      // 最大仓位比例（如0.80=80%）

    // ===== 时间控制 =====
    private static final LocalTime MARKET_OPEN      = LocalTime.of(9, 30);
    private static final LocalTime MORNING_END      = LocalTime.of(11, 30);
    private static final LocalTime AFTERNOON_START  = LocalTime.of(13, 0);
    private static final LocalTime NO_NEW_BUY_AFTER = LocalTime.of(14, 45);
    private static final LocalTime MARKET_CLOSE     = LocalTime.of(15, 0);

    // ===== 日内价格追踪（每日开盘时重置）=====
    private double todayHigh     = 0;     // 今日最高价（实时更新）
    private double todayLow      = Double.MAX_VALUE; // 今日最低价（实时更新）
    private double openPrice     = 0;     // 今日开盘价
    private double prevClose     = 0;     // 昨日收盘价
    private LocalDate trackDate  = null;  // 追踪日期（用于每日重置）

    // 历史分钟价格窗口（用于检测高点/低点反转）
    private final java.util.Deque<Double> recentPrices = new java.util.ArrayDeque<>();
    private static final int PRICE_WINDOW = 5; // 保留最近5个价格点

    public IntradayTradingStrategy() {
        this(0.03, 0.08, 0.05, 0.015, 0.80);
    }

    public IntradayTradingStrategy(StrategyConfig sc) {
        this(
            sc.getStopLossPercent(),
            sc.getTakeProfitFullPercent(),
            sc.getTakeProfitHalfPercent(),
            sc.getDoTProfitPercent() > 0 ? sc.getDoTProfitPercent() : 0.015,
            sc.getMaxPositionRatio()
        );
    }

    public IntradayTradingStrategy(double stopLossPercent, double takeProfitFull,
                                    double takeProfitHalf, double doTProfitMin,
                                    double maxPositionRatio) {
        this.stopLossPercent  = stopLossPercent;
        this.takeProfitFull   = takeProfitFull;
        this.takeProfitHalf   = takeProfitHalf;
        this.doTProfitMin     = doTProfitMin;
        this.maxPositionRatio = maxPositionRatio;
    }

    @Override
    public String getStrategyName() { return "日内做T策略"; }

    @Override
    public String getDescription() {
        return String.format(
            "日内做T：止损%.0f%% | 清仓止盈%.0f%% | 减仓止盈%.0f%% | 做T最低收益%.1f%% | 最大仓位%.0f%%",
            stopLossPercent * 100, takeProfitFull * 100, takeProfitHalf * 100,
            doTProfitMin * 100, maxPositionRatio * 100);
    }

    @Override
    public int getMinBarsRequired() { return 5; }

    /** 供外部快速止损检查使用 */
    public double getStopLossPercent()       { return stopLossPercent; }
    public double getTakeProfitFullPercent() { return takeProfitFull; }
    public double getTakeProfitHalfPercent() { return takeProfitHalf; }

    @Override
    public TradeSignal generateSignal(String stockCode, String stockName,
                                       List<StockBar> bars, AnalysisResult analysis,
                                       Portfolio portfolio) {
        if (bars == null || bars.isEmpty()) {
            return hold(stockCode, stockName, "无K线数据");
        }

        // 取最新价格（bars最后一根，通常是追加的实时Bar）
        StockBar latestBar = bars.get(bars.size() - 1);
        double currentPrice = latestBar.getClose();
        if (currentPrice <= 0) return hold(stockCode, stockName, "实时价格无效");

        LocalTime now = LocalTime.now();

        // ===== 每日开盘重置日内追踪数据 =====
        LocalDate today = LocalDate.now();
        if (trackDate == null || !trackDate.equals(today)) {
            resetDailyTracking(today);
        }

        // 更新今日最高/最低价
        updateDailyTracking(latestBar);

        // 更新近期价格窗口（用于趋势检测）
        updatePriceWindow(currentPrice);

        boolean hasPosition  = portfolio != null && portfolio.hasPosition(stockCode);
        boolean hasAvailable = hasPosition &&
                portfolio.getPosition(stockCode).getAvailableQuantity() > 0;

        // ===== 非交易时段：不操作 =====
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) {
            return hold(stockCode, stockName, "非交易时段");
        }

        // ===== 已持仓：止损/止盈/高点做T减仓 =====
        // 午休判断移到这里之后：止损逻辑全天有效（包括午休期间），仅阅止新建仓
        if (hasPosition) {
            Position pos = portfolio.getPosition(stockCode);
            double avgCost = pos.getAvgCost();
            if (avgCost <= 0) return hold(stockCode, stockName, "成本价无效");

            double profitRate = (currentPrice - avgCost) / avgCost;

            // === 1. 止损（最优先）===
            if (hasAvailable) {
                double stopPrice = calcStopPrice(bars, avgCost);
                if (currentPrice <= stopPrice) {
                    return buildSignal(stockCode, stockName,
                            TradeSignal.SignalType.STOP_LOSS, 100, currentPrice,
                            String.format("日内止损：成本=%.2f 现价=%.2f 止损价=%.2f 亏损=%.2f%%",
                                    avgCost, currentPrice, stopPrice, profitRate * 100));
                }
            }

            // === 2. 清仓止盈 ===
            if (hasAvailable && profitRate >= takeProfitFull) {
                return buildSignal(stockCode, stockName,
                        TradeSignal.SignalType.TAKE_PROFIT, 95, currentPrice,
                        String.format("清仓止盈：盈利=%.2f%%（>=%.0f%%）清仓",
                                profitRate * 100, takeProfitFull * 100));
            }

            // === 3. 尾盘处理（14:45后）===
            if (now.isAfter(NO_NEW_BUY_AFTER)) {
                if (hasAvailable) {
                    if (profitRate >= 0.005) {
                        // 有浮盈（>=0.5%）：尾盘清仓锁利，不留隔夜风险
                        return buildSignal(stockCode, stockName,
                                TradeSignal.SignalType.TAKE_PROFIT, 90, currentPrice,
                                String.format("尾盘清仓锁利：盈利=%.2f%%，锁定日内收益", profitRate * 100));
                    } else if (profitRate >= 0.002) {
                        // 微薄盈利（0.2%~0.5%）：减仓70%，减少风险
                        return buildSignal(stockCode, stockName,
                                TradeSignal.SignalType.SELL, 70, currentPrice,
                                String.format("尾盘减仓70%%：盈利=%.2f%%，减少隔夜敞口", profitRate * 100));
                    }
                    // 亏损或微亏：不操作，持仓等待T+1做T机会
                }
                return hold(stockCode, stockName, String.format("尾盘观望：浮盈=%.2f%%（不足减仓门槛）", profitRate * 100));
            }

            // === 4. 高点做T减仓 ===
            if (hasAvailable && profitRate >= doTProfitMin) {
                boolean nearTodayHigh = todayHigh > 0 && currentPrice >= todayHigh * 0.98;
                boolean priceDownTrend = isPriceDownTrend(); // 价格开始下行

                if (nearTodayHigh && priceDownTrend) {
                    return buildSignal(stockCode, stockName,
                            TradeSignal.SignalType.SELL, 82, currentPrice,
                            String.format("做T高点减仓：盈利=%.2f%% 现价=%.2f 今日高点=%.2f（高点-2%%内），价格开始下行",
                                    profitRate * 100, currentPrice, todayHigh));
                }

                // 减仓止盈（盈利超过takeProfitHalf）
                if (profitRate >= takeProfitHalf) {
                    return buildSignal(stockCode, stockName,
                            TradeSignal.SignalType.TAKE_PROFIT, 88, currentPrice,
                            String.format("减仓止盈：盈利=%.2f%%（>=%.0f%%），做T减仓50%%",
                                    profitRate * 100, takeProfitHalf * 100));
                }

                // 均线死叉卖出
                if (bars.size() >= 5) {
                    double[] closes = TechnicalIndicator.extractCloses(bars);
                    TechnicalIndicator.MACDResult macd = TechnicalIndicator.macd(closes);
                    if (macd.isDeathCross() && nearTodayHigh) {
                        return buildSignal(stockCode, stockName,
                                TradeSignal.SignalType.SELL, 78, currentPrice,
                                String.format("做T减仓（MACD死叉+接近高点）：盈利=%.2f%%", profitRate * 100));
                    }
                }
            }
        }

        // ===== 买入逻辑 =====
        // 尾盘不新建仓（14:45后）
        if (now.isAfter(NO_NEW_BUY_AFTER)) {
            return hold(stockCode, stockName, "尾盘不新建仓");
        }
        // 午休时段不新建仓（11:30-13:00）——止损逻辑已在上方执行，仅限制新建仓
        if (now.isAfter(MORNING_END) && now.isBefore(AFTERNOON_START)) {
            return hold(stockCode, stockName, "午休时段，不新建仓");
        }
        // 开盘后3分钟内（9:30-9:33）不追涨开盘跳空，等待行情稳定
        if (now.isBefore(LocalTime.of(9, 33))) {
            return hold(stockCode, stockName, "开盘冷静期（9:30-9:33），等待行情稳定");
        }

        // 今日已买入，T+1限制（实盘不能当天买卖同一只）
        // 注意：做T策略中，当日已有可用仓位的情况下，卖出是可以的；
        // 但若今日刚买入（lastBuyDate=today），则该仓位当日不可卖（T+1），不影响再次判断买入
        if (hasPosition) {
            Position pos = portfolio.getPosition(stockCode);
            if (pos.getLastBuyDate() != null && pos.getLastBuyDate().equals(today)) {
                // 今日已买过，不重复开仓（避免越亏越买）
                return hold(stockCode, stockName, "今日已建仓，等待T+1做T卖出");
            }
        }

        // ===== 低点买入判断 =====
        boolean nearTodayLow = todayLow < Double.MAX_VALUE && currentPrice <= todayLow * 1.02;
        boolean priceUpTrend = isPriceUpTrend();  // 价格开始企稳/上行

        // 昨收参考（用日线bars倒数第2根的收盘价，或预存的prevClose）
        double pc = prevClose > 0 ? prevClose : (bars.size() >= 2 ? bars.get(bars.size() - 2).getClose() : 0);
        boolean notBreakDown = pc <= 0 || currentPrice >= pc * 0.97; // 不低于昨收-3%

        // 早盘低吸（9:33~10:30）：开盘后回调>=1.5%且企稳
        boolean earlyDipBuy = false;
        if (now.isBefore(LocalTime.of(10, 30)) && openPrice > 0) {
            double dropFromOpen = (openPrice - currentPrice) / openPrice;
            earlyDipBuy = dropFromOpen >= 0.015 && priceUpTrend && notBreakDown;
        }

        // 技术面评分
        int techScore = (analysis != null) ? analysis.getOverallScore() : 50;

        // 买入：接近今日低点 + 价格企稳上行 + 技术面不差
        if ((nearTodayLow && priceUpTrend && notBreakDown && techScore >= 55) || earlyDipBuy) {
            String buyReason;
            int strength;
            if (earlyDipBuy) {
                buyReason = String.format("早盘低吸：开盘=%.2f 现价=%.2f 回调=%.2f%% 企稳上行",
                        openPrice, currentPrice, (openPrice - currentPrice) / openPrice * 100);
                strength = 78;
            } else {
                buyReason = String.format("低点买入：现价=%.2f 今日低点=%.2f（低点+2%%内），价格企稳，技术分=%d",
                        currentPrice, todayLow, techScore);
                strength = 75;
            }

            double stopPrice = calcStopPrice(bars, currentPrice);

            return TradeSignal.builder()
                    .signalId(UUID.randomUUID().toString())
                    .stockCode(stockCode).stockName(stockName)
                    .signalType(TradeSignal.SignalType.BUY).strength(strength)
                    .suggestedPrice(currentPrice)
                    .positionRatio(maxPositionRatio)
                    .stopLossPrice(stopPrice)
                    .takeProfitPrice(currentPrice * (1 + takeProfitFull))
                    .strategyName(getStrategyName())
                    .signalTime(LocalDateTime.now())
                    .reason(buyReason)
                    .build();
        }

        return hold(stockCode, stockName,
                String.format("观望中：现价=%.2f 今日高=%s 今日低=%s 企稳=%s",
                        currentPrice,
                        todayHigh > 0 ? String.format("%.2f", todayHigh) : "N/A",
                        todayLow < Double.MAX_VALUE ? String.format("%.2f", todayLow) : "N/A",
                        priceUpTrend ? "是" : "否"));
    }

    @Override
    public double calculatePositionSize(TradeSignal signal, Portfolio portfolio) {
        // 日内做T：信号强度>=80用满仓，其他用80%仓位
        if (signal.getStrength() >= 80) return maxPositionRatio;
        return maxPositionRatio * 0.8;
    }

    // ===== 辅助方法 =====

    /** 每日开盘时重置日内追踪数据 */
    private void resetDailyTracking(LocalDate date) {
        trackDate = date;
        todayHigh = 0;
        todayLow  = Double.MAX_VALUE;
        openPrice = 0;
        prevClose = 0;
        recentPrices.clear();
        log.info("[日内做T] 新交易日 {} 开始，重置日内高低点追踪", date);
    }

    /** 更新今日最高/最低价、开盘价 */
    private void updateDailyTracking(StockBar bar) {
        double h = bar.getHigh();
        double l = bar.getLow();
        double o = bar.getOpen();
        double c = bar.getClose();

        if (h > 0 && h > todayHigh)     todayHigh = h;
        if (l > 0 && l < todayLow)      todayLow  = l;
        if (openPrice <= 0 && o > 0)    openPrice = o;
        // prevClose：用最近日线bars倒数第2根的收盘价
        if (prevClose <= 0 && c > 0)    prevClose = c; // 初始化（会被外部日线数据覆盖）
    }

    /** 更新近期价格窗口 */
    private void updatePriceWindow(double price) {
        recentPrices.addLast(price);
        while (recentPrices.size() > PRICE_WINDOW) {
            recentPrices.pollFirst();
        }
    }

    /**
     * 判断价格是否处于下行趋势（近期价格连续下行）
     * 用于检测高点反转信号（应卖出）
     */
    private boolean isPriceDownTrend() {
        if (recentPrices.size() < 3) return false;
        Double[] arr = recentPrices.toArray(new Double[0]);
        int n = arr.length;
        // 最近3个价格点中，至少2个连续下行
        int downCount = 0;
        for (int i = n - 1; i >= 1; i--) {
            if (arr[i] < arr[i - 1]) downCount++;
            else break;
        }
        return downCount >= 2;
    }

    /**
     * 判断价格是否处于上行趋势（近期价格企稳/连续上行）
     * 用于检测低点企稳信号（可买入）
     */
    private boolean isPriceUpTrend() {
        if (recentPrices.size() < 3) return false; // 数据不足时保守不买（避免无趋势确认时误买）
        Double[] arr = recentPrices.toArray(new Double[0]);
        int n = arr.length;
        // 最近3个价格点中，至少2个连续上行
        int upCount = 0;
        for (int i = n - 1; i >= 1; i--) {
            if (arr[i] > arr[i - 1]) upCount++;
            else break;
        }
        return upCount >= 2;
    }

    /** 计算止损价（ATR动态止损与固定止损取较紧者）
     * [P1-2] 自适应ATR倍数：低波动股(ATR%<=1%)用2.5倍，中波动用1.5倍，高波动(ATR%>2.5%)用1.2倍
     */
    private double calcStopPrice(List<StockBar> bars, double cost) {
        double fixedStop = cost * (1 - stopLossPercent);
        if (bars.size() >= 14) {
            // [P1-2] 根据ATR%动态选择倍数
            double atrPct = AtrStopLoss.atrPercent(bars, 14) * 100;
            double multiplier;
            if (atrPct <= 0)         multiplier = 1.5; // 无法计算时用默认值
            else if (atrPct <= 1.0)  multiplier = 2.5; // 低波动：宽止损，避免正常波动触发
            else if (atrPct <= 2.5)  multiplier = 1.5; // 中波动：标准配置
            else                     multiplier = 1.2; // 高波动：收紧止损，防单笔大亏
            double atrStop = AtrStopLoss.calcStopPrice(bars, cost, 14, multiplier);
            return Math.max(atrStop, fixedStop); // 取较高者（更紧的止损）
        }
        return fixedStop;
    }

    private TradeSignal buildSignal(String code, String name, TradeSignal.SignalType type,
                                     int strength, double price, String reason) {
        return TradeSignal.builder()
                .signalId(UUID.randomUUID().toString())
                .stockCode(code).stockName(name)
                .signalType(type).strength(strength)
                .suggestedPrice(price)
                .strategyName(getStrategyName())
                .signalTime(LocalDateTime.now())
                .reason(reason)
                .build();
    }

    private TradeSignal hold(String code, String name, String reason) {
        return TradeSignal.builder()
                .signalId(UUID.randomUUID().toString())
                .stockCode(code).stockName(name)
                .signalType(TradeSignal.SignalType.HOLD).strength(0)
                .strategyName(getStrategyName())
                .signalTime(LocalDateTime.now())
                .reason(reason)
                .build();
    }
}


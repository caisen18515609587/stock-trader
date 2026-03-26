package com.stocktrader.strategy;

import com.stocktrader.analysis.AtrStopLoss;
import com.stocktrader.analysis.TechnicalIndicator;
import com.stocktrader.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 中长期交易策略（含 ATR 动态止损）
 * <p>
 * 核心思路：
 * 1. 【选股建仓】：基于均线多头 + MACD + 量能选择上升趋势中的股票
 * 2. 【加仓条件】：突破前高/均线金叉/量能放大确认趋势，分批加仓
 * 3. 【持仓管理】：最少持仓 minHoldDays 天，不频繁操作
 * 4. 【止损】：ATR 动态止损（吊灯止损）or 固定比例止损（可配置）
 * 5. 【止盈】：分级止盈，不一次全出
 * <p>
 * ATR 吊灯止损原理：
 *   止损价 = max(入场价 - N×ATR, 持仓最高价 - N×ATR)
 *   → 随股价上涨自动上移止损线，保护浮盈，同时根据波动率自适应宽窄
 */
public class MediumLongTermStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(MediumLongTermStrategy.class);

    private final double stopLossPercent;
    private final double takeProfitHalf;
    private final double takeProfitFull;
    private final double maxPositionRatio;
    private final int maxPositions;
    private final int minHoldDays;
    private final int minScore;

    // ATR 动态止损参数
    /** ATR 周期（默认14日）*/
    private final int atrPeriod;
    /** ATR 倍数：止损价 = 最高价 - atrMultiplier × ATR（默认2.0）*/
    private final double atrMultiplier;
    /** true=启用ATR动态止损，false=固定比例止损 */
    private final boolean useAtrStop;

    public MediumLongTermStrategy() {
        this(0.07, 0.12, 0.20, 0.40, 3, 5, 65, 14, 2.0, true);
    }

    /** 从 StrategyConfig 构建 */
    public MediumLongTermStrategy(StrategyConfig sc) {
        this(
            sc.getStopLossPercent(),
            sc.getTakeProfitHalfPercent(),
            sc.getTakeProfitFullPercent(),
            sc.getMaxPositionRatio(),
            sc.getMaxPositions(),
            sc.getMinHoldDays() > 0 ? sc.getMinHoldDays() : 5,
            sc.getMinScore() > 0 ? sc.getMinScore() : 65,
            14, 2.0, true
        );
    }

    public MediumLongTermStrategy(double stopLossPercent, double takeProfitHalf,
                                   double takeProfitFull, double maxPositionRatio,
                                   int maxPositions, int minHoldDays, int minScore) {
        this(stopLossPercent, takeProfitHalf, takeProfitFull, maxPositionRatio,
             maxPositions, minHoldDays, minScore, 14, 2.0, true);
    }

    public MediumLongTermStrategy(double stopLossPercent, double takeProfitHalf,
                                   double takeProfitFull, double maxPositionRatio,
                                   int maxPositions, int minHoldDays, int minScore,
                                   int atrPeriod, double atrMultiplier, boolean useAtrStop) {
        this.stopLossPercent = stopLossPercent;
        this.takeProfitHalf = takeProfitHalf;
        this.takeProfitFull = takeProfitFull;
        this.maxPositionRatio = maxPositionRatio;
        this.maxPositions = maxPositions;
        this.minHoldDays = minHoldDays;
        this.minScore = minScore;
        this.atrPeriod = atrPeriod;
        this.atrMultiplier = atrMultiplier;
        this.useAtrStop = useAtrStop;
    }

    @Override
    public String getStrategyName() {
        return "中长期策略";
    }

    /** 获取固定止损比例（供外部快速止损/止盈检查使用） */
    public double getStopLossPercent() { return stopLossPercent; }

    /** 获取清仓止盈比例（供外部快速止盈检查使用） */
    public double getTakeProfitFullPercent() { return takeProfitFull; }

    /** 获取减仓止盈比例（供外部追踪止盈检查使用） */
    public double getTakeProfitHalfPercent() { return takeProfitHalf; }

    @Override
    public String getDescription() {
        String stopDesc = useAtrStop
                ? String.format("ATR(%d)×%.1f动态止损", atrPeriod, atrMultiplier)
                : String.format("固定止损%.0f%%", stopLossPercent * 100);
        return String.format("中长期：%s  减仓%.0f%%  清仓%.0f%%  最少持仓%d天",
                stopDesc, takeProfitHalf * 100, takeProfitFull * 100, minHoldDays);
    }

    @Override
    public int getMinBarsRequired() {
        return 60;
    }

    @Override
    public TradeSignal generateSignal(String stockCode, String stockName,
                                       List<StockBar> bars, AnalysisResult analysis,
                                       Portfolio portfolio) {
        if (!isApplicable(bars)) {
            return hold(stockCode, stockName, "K线数据不足");
        }

        double[] closes = TechnicalIndicator.extractCloses(bars);
        double currentPrice = closes[closes.length - 1];
        boolean hasPosition  = portfolio != null && portfolio.hasPosition(stockCode);
        boolean hasAvailable = hasPosition &&
                portfolio.getPosition(stockCode).getAvailableQuantity() > 0;

        // ===== 已持仓：检查止损/止盈 =====
        if (hasPosition) {
            Position pos = portfolio.getPosition(stockCode);
            double avgCost   = pos.getAvgCost();
            double profitRate = avgCost > 0 ? (currentPrice - avgCost) / avgCost : 0;

            // ==== 计算当前有效止损价（ATR动态 or 固定比例）====
            double effectiveStopPrice;
            String stopDesc;
            if (useAtrStop && bars.size() >= atrPeriod + 1) {
                // 吊灯止损：持仓最高价 - N×ATR
                double chandelierPrice = AtrStopLoss.chandelierStop(bars, atrPeriod, atrMultiplier);
                // 保底：固定止损（防止ATR止损比固定止损更宽）
                double fixedStopPrice  = avgCost * (1 - stopLossPercent);
                effectiveStopPrice = Math.max(chandelierPrice, fixedStopPrice);
                double atrPct = AtrStopLoss.atrPercent(bars, atrPeriod) * 100;
                stopDesc = String.format("ATR动态止损=%.2f（ATR=%.1f%%×%.1f，吊灯=%.2f，固定=%.2f）",
                        effectiveStopPrice, atrPct, atrMultiplier, chandelierPrice, fixedStopPrice);
            } else {
                effectiveStopPrice = avgCost * (1 - stopLossPercent);
                stopDesc = String.format("固定止损=%.2f（%.0f%%）", effectiveStopPrice, stopLossPercent * 100);
            }

            // 最少持仓天数（但止损无视）
            boolean canSell = true;
            if (pos.getFirstBuyTime() != null) {
                long heldDays = ChronoUnit.DAYS.between(pos.getFirstBuyTime().toLocalDate(),
                        java.time.LocalDate.now());
                if (heldDays < minHoldDays && currentPrice > effectiveStopPrice) {
                    canSell = false;
                }
            }

            if (hasAvailable) {
                // 1. 止损（无视最少持仓天数）
                if (currentPrice <= effectiveStopPrice) {
                    return TradeSignal.builder()
                            .signalId(UUID.randomUUID().toString())
                            .stockCode(stockCode).stockName(stockName)
                            .signalType(TradeSignal.SignalType.STOP_LOSS).strength(100)
                            .suggestedPrice(currentPrice).strategyName(getStrategyName())
                            .signalTime(LocalDateTime.now())
                            .reason(String.format("中长期止损：成本=%.2f 现价=%.2f 亏损=%.2f%%  %s",
                                    avgCost, currentPrice, profitRate * 100, stopDesc))
                            .build();
                }

                if (canSell) {
                    // ==== 计算 ATR 动态止盈目标 ====
                    // 根据该股票近期波动率（ATR%）自适应调整止盈线：
                    //   高波动票（如小盘题材股）→ 目标上调，顺势多拿利润
                    //   低波动票（如蓝筹价值股）→ 目标下调，及时锁利
                    double dynTakeProfitFull = AtrStopLoss.dynamicTakeProfitRate(bars, atrPeriod, takeProfitFull);
                    double dynTakeProfitHalf = AtrStopLoss.dynamicTakeProfitRate(bars, atrPeriod, takeProfitHalf);
                    String tpDesc = AtrStopLoss.dynamicTakeProfitDesc(bars, atrPeriod, takeProfitFull);

                    // 2. 清仓止盈
                    if (profitRate >= dynTakeProfitFull) {
                        return TradeSignal.builder()
                                .signalId(UUID.randomUUID().toString())
                                .stockCode(stockCode).stockName(stockName)
                                .signalType(TradeSignal.SignalType.TAKE_PROFIT).strength(90)
                                .suggestedPrice(currentPrice).strategyName(getStrategyName())
                                .signalTime(LocalDateTime.now())
                                .reason(String.format("中长期清仓止盈：盈利=%.2f%%（动态目标=%.1f%%）  %s",
                                        profitRate * 100, dynTakeProfitFull * 100, tpDesc))
                                .build();
                    }

                    // 3. 减仓止盈
                    // 【优化】取消"趋势走弱"强制确认：强势股持续上涨时 MACD 不会死叉、也不会跌破 MA20，
                    // 导致原逻辑在强势股身上永远无法触发减仓止盈，只能坐等利润回吐。
                    // 新逻辑：
                    //   - 盈利 >= 减仓目标（dynTakeProfitHalf）即可触发减仓50%
                    //   - 若同时趋势走弱（MACD死叉 or 跌破MA20）则力度升级为减仓70%（更激进锁利）
                    if (profitRate >= dynTakeProfitHalf) {
                        TechnicalIndicator.MACDResult macd = TechnicalIndicator.macd(closes);
                        boolean trendWeak = macd.isDeathCross() ||
                                (analysis != null && currentPrice < analysis.getMa20());
                        String sellDesc = trendWeak
                                ? String.format("中长期减仓：盈利=%.2f%%且趋势走弱，卖出70%%  %s",
                                    profitRate * 100,
                                    AtrStopLoss.dynamicTakeProfitDesc(bars, atrPeriod, takeProfitHalf))
                                : String.format("中长期减仓：盈利=%.2f%%达目标，卖出50%%  %s",
                                    profitRate * 100,
                                    AtrStopLoss.dynamicTakeProfitDesc(bars, atrPeriod, takeProfitHalf));
                        // 趋势走弱时信号原因加入"70%"让 executeSell 卖出更多仓位
                        return TradeSignal.builder()
                                .signalId(UUID.randomUUID().toString())
                                .stockCode(stockCode).stockName(stockName)
                                .signalType(TradeSignal.SignalType.TAKE_PROFIT)
                                .strength(trendWeak ? 85 : 80)
                                .suggestedPrice(currentPrice).strategyName(getStrategyName())
                                .signalTime(LocalDateTime.now())
                                .reason(sellDesc)
                                .build();
                    }

                    // 4. 趋势逆转止损（跌破MA60 + MACD死叉）
                    if (analysis != null && currentPrice < analysis.getMa60()) {
                        TechnicalIndicator.MACDResult macd = TechnicalIndicator.macd(closes);
                        if (macd.isDeathCross() && profitRate < 0.05) {
                            return TradeSignal.builder()
                                    .signalId(UUID.randomUUID().toString())
                                    .stockCode(stockCode).stockName(stockName)
                                    .signalType(TradeSignal.SignalType.SELL).strength(75)
                                    .suggestedPrice(currentPrice).strategyName(getStrategyName())
                                    .signalTime(LocalDateTime.now())
                                    .reason(String.format("趋势逆转：跌破MA60且MACD死叉，盈亏=%.2f%%",
                                            profitRate * 100))
                                    .build();
                        }
                    }
                }
            }
        }

        // ===== 买入逻辑 =====
        if (portfolio != null && portfolio.getPositions().size() >= maxPositions) {
            return hold(stockCode, stockName, "持仓已满" + maxPositions + "只");
        }
        if (hasPosition) {
            // ===== 金字塔加仓逻辑 =====
            // 条件：当前持仓处于盈利且趋势强劲时，允许加仓最多1次（总加仓量<=首次建仓50%）
            // 触发条件（全部满足）：
            //   1. 当前盈利 >= 5%（确认趋势有效，避免在亏损位置加码）
            //   2. 均线多头排列（MA5 > MA20 > MA60）
            //   3. MACD 在零轴以上且DIF向上（强势区域）
            //   4. 当前仓位尚未超过最大加仓次数（通过firstBuyTime+lastBuyDate判断）
            Position pos = portfolio.getPosition(stockCode);
            if (pos != null && pos.getAvailableQuantity() > 0) {
                double avgCost = pos.getAvgCost();
                double profitRateNow = avgCost > 0 ? (currentPrice - avgCost) / avgCost : 0;
                // 加仓条件1：已有不错浮盈（>=5%）
                boolean isProfitable = profitRateNow >= 0.05;
                // 加仓条件2：均线多头排列
                boolean bullishMA = analysis != null &&
                        TechnicalIndicator.isBullishAlignment(analysis.getMa5(), analysis.getMa10(),
                                analysis.getMa20(), analysis.getMa60());
                // 加仓条件3：MACD强势区（零轴以上且DIF>0）
                TechnicalIndicator.MACDResult macdAdd = TechnicalIndicator.macd(closes);
                boolean macdStrong = macdAdd.latestDif() > 0 && macdAdd.latestMacd() > 0;
                // 加仓条件4：首次买入到现在已过至少5天（避免追高刚买就再加）
                boolean holdLongEnough = pos.getFirstBuyTime() != null &&
                        ChronoUnit.DAYS.between(pos.getFirstBuyTime().toLocalDate(),
                                java.time.LocalDate.now()) >= 5;
                // 加仓条件5：上次操作距今至少5天（防止频繁加仓，每只票最多加仓1次/5天）
                boolean cooldownOk = pos.getLastUpdateTime() == null ||
                        ChronoUnit.DAYS.between(pos.getLastUpdateTime().toLocalDate(),
                                java.time.LocalDate.now()) >= 5;

                // [P2-2] 加仓条件6：当前价不高于20日均线的1.05倍（避免高位追涨加仓）
                // 原理：当股价已显著高于MA20（乖离率过大）时，短期回调风险增加
                //       此时应等待股价回归均线附近再加仓，而非高位追涨
                boolean priceNearMA20 = analysis == null || // 无分析数据时跳过此检查
                        currentPrice <= analysis.getMa20() * 1.05;

                // [P2-2] 日志输出：记录被过滤的加仓信号原因
                if (isProfitable && bullishMA && macdStrong && holdLongEnough && cooldownOk && !priceNearMA20) {
                    double deviationPct = analysis != null
                            ? (currentPrice / analysis.getMa20() - 1) * 100
                            : 0;
                    log.info("[P2-2] {} {} 加仓被过滤：当前价={:.2f} 高于MA20={:.2f}的{:.1f}%，乖离率过大",
                            stockCode, stockName, currentPrice, analysis.getMa20(), deviationPct);
                }

                if (isProfitable && bullishMA && macdStrong && holdLongEnough && cooldownOk && priceNearMA20) {
                    double ma20Dist = analysis != null
                            ? (currentPrice / analysis.getMa20() - 1) * 100
                            : 0;
                    return TradeSignal.builder()
                            .signalId(UUID.randomUUID().toString())
                            .stockCode(stockCode).stockName(stockName)
                            .signalType(TradeSignal.SignalType.BUY)
                            .strength(72)
                            .suggestedPrice(currentPrice)
                            .strategyName(getStrategyName())
                            .signalTime(LocalDateTime.now())
                            .reason(String.format("[加仓] 浮盈=%.1f%% 均线多头+MACD强势 MA20乖离=%.1f%%，金字塔补仓30%%仓位",
                                    profitRateNow * 100, ma20Dist))
                            .build();
                }
            }
            return hold(stockCode, stockName, "已持有，等待加仓或止盈条件");
        }

        int score = analysis != null ? analysis.getOverallScore() : 0;
        if (score < minScore) {
            return hold(stockCode, stockName, String.format("评分%d < 最低%d", score, minScore));
        }

        TechnicalIndicator.MACDResult macd = TechnicalIndicator.macd(closes);
        TechnicalIndicator.KDJResult kdj = TechnicalIndicator.kdj(bars);
        double volumeRatio = TechnicalIndicator.volumeRatio(bars, 20);

        // ===== 量比前置过滤 =====
        // 量比 < 0.8 意味着当日成交量显著低于5日均量：
        //   1. 价格涨幅缺乏成交量支撑（无量空涨），极易迅速回落
        //   2. 机构资金未进场，大概率是小单游资拉升或指数拉抬
        // 此类情形应等待有效放量确认后再入场
        if (volumeRatio > 0 && volumeRatio < 0.8) {
            return hold(stockCode, stockName, String.format("量比=%.2f<0.8，无量空涨风险，等待放量确认", volumeRatio));
        }

        List<String> reasons = new ArrayList<>();

        // 均线多头排列（MA5>MA20>MA60）
        if (analysis != null &&
                TechnicalIndicator.isBullishAlignment(
                        analysis.getMa5(), analysis.getMa10(),
                        analysis.getMa20(), analysis.getMa60())) {
            reasons.add("均线多头");
        }
        if (macd.isGoldenCross()) reasons.add("MACD金叉");
        else if (macd.latestDif() > 0 && macd.latestMacd() > 0) reasons.add("MACD强势区");
        if (kdj.isGoldenCross()) reasons.add("KDJ金叉");
        if (volumeRatio >= 1.5) reasons.add(String.format("放量%.1f倍", volumeRatio));
        if (score >= 75) reasons.add(String.format("高评分%d", score));
        if (analysis != null && currentPrice > analysis.getMa20() && currentPrice > analysis.getMa60()) {
            reasons.add("站上MA20/MA60");
        }

        boolean canBuy = reasons.size() >= 3 && score >= minScore;
        canBuy = canBuy || (reasons.size() >= 2 && score >= minScore + 10);

        if (canBuy) {
            int strength = Math.min(55 + reasons.size() * 7 + Math.max(0, score - 65), 90);
            double posRatio = strength >= 80 ? maxPositionRatio
                    : (strength >= 70 ? maxPositionRatio * 0.7 : maxPositionRatio * 0.5);

            // 计算 ATR 止损价用于信号输出
            double atrStopPrice = (useAtrStop && bars.size() >= atrPeriod + 1)
                    ? AtrStopLoss.calcStopPrice(bars, currentPrice, atrPeriod, atrMultiplier)
                    : currentPrice * (1 - stopLossPercent);
            double stopStr = useAtrStop ? atrMultiplier : stopLossPercent * 100;
            String stopLabel = useAtrStop
                    ? String.format("ATR×%.1f止损=%.2f", stopStr, atrStopPrice)
                    : String.format("固定%.0f%%止损=%.2f", stopStr, atrStopPrice);

            return TradeSignal.builder()
                    .signalId(UUID.randomUUID().toString())
                    .stockCode(stockCode).stockName(stockName)
                    .signalType(strength >= 80 ? TradeSignal.SignalType.STRONG_BUY : TradeSignal.SignalType.BUY)
                    .strength(strength)
                    .suggestedPrice(currentPrice)
                    .positionRatio(posRatio)
                    .stopLossPrice(atrStopPrice)
                    .takeProfitPrice(currentPrice * (1 + takeProfitFull))
                    .strategyName(getStrategyName())
                    .signalTime(LocalDateTime.now())
                    .reason(String.format("中长期买入：%s  %s",
                            String.join("，", reasons), stopLabel))
                    .build();
        }

        return hold(stockCode, stockName,
                String.format("条件不足（评分%d，%d个信号）", score, reasons.size()));
    }

    @Override
    public double calculatePositionSize(TradeSignal signal, Portfolio portfolio) {
        if (signal.getStrength() >= 80) return maxPositionRatio;
        if (signal.getStrength() >= 70) return maxPositionRatio * 0.7;
        return maxPositionRatio * 0.5;
    }

    private TradeSignal hold(String code, String name, String reason) {
        return TradeSignal.builder()
                .signalId(UUID.randomUUID().toString())
                .stockCode(code).stockName(name)
                .signalType(TradeSignal.SignalType.HOLD).strength(0)
                .strategyName(getStrategyName())
                .signalTime(LocalDateTime.now()).reason(reason)
                .build();
    }
}


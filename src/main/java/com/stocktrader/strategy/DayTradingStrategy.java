package com.stocktrader.strategy;

import com.stocktrader.analysis.AtrStopLoss;
import com.stocktrader.analysis.TechnicalIndicator;
import com.stocktrader.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 短线做T策略（含 ATR 动态止损）
 * <p>
 * 核心思路：
 * 1. 【建仓/加仓】：寻找日内低点买入，目标次日高开或当日反弹
 * 2. 【做T卖出】：在日内高点减仓，锁定短线收益
 * 3. 【止损】：ATR动态止损（1.5倍ATR）or 固定3%止损（可配置）
 *              短线ATR倍数较小（1.5），止损更紧
 * 4. 【止盈】：盈利超过 5% 减仓一半，超过 8% 清仓
 * 5. 【尾盘策略】：14:45后根据盈利档位决定减仓比例，减少隔夜风险
 *              盈利>3% → 触发清仓信号（TAKE_PROFIT+清仓），不留隔夜
 *              盈利1%~3% → 普通减仓信号（卖出70%），减少隔夜敞口
 *              盈利<1% → 不操作（手续费吃掉收益）
 * 6. 【买入过滤】：当日实时价低于昨收 -2% 时拒绝买入（日线强但日内已破位）
 */
public class DayTradingStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(DayTradingStrategy.class);

    // 止损/止盈参数
    private final double stopLossPercent;
    private final double takeProfitHalf;
    private final double takeProfitFull;
    private final double doTProfit;
    private final double maxPositionRatio;

    // ATR 动态止损参数（短线用更小的周期和倍数）
    private final int    atrPeriod;
    private final double atrMultiplier;
    private final boolean useAtrStop;

    private static final LocalTime NO_NEW_POSITION_AFTER = LocalTime.of(14, 45);
    private static final LocalTime MARKET_OPEN           = LocalTime.of(9, 31);

    public DayTradingStrategy() {
        this(0.03, 0.05, 0.08, 0.02, 0.35, 14, 1.5, true);
    }

    /** 从 StrategyConfig 构建 */
    public DayTradingStrategy(StrategyConfig sc) {
        this(
            sc.getStopLossPercent(),
            sc.getTakeProfitHalfPercent(),
            sc.getTakeProfitFullPercent(),
            sc.getDoTProfitPercent() > 0 ? sc.getDoTProfitPercent() : 0.02,
            sc.getMaxPositionRatio(),
            14, 1.5, true
        );
    }

    public DayTradingStrategy(double stopLossPercent, double takeProfitHalf,
                               double takeProfitFull, double doTProfit, double maxPositionRatio) {
        this(stopLossPercent, takeProfitHalf, takeProfitFull, doTProfit, maxPositionRatio,
             14, 1.5, true);
    }

    public DayTradingStrategy(double stopLossPercent, double takeProfitHalf,
                               double takeProfitFull, double doTProfit, double maxPositionRatio,
                               int atrPeriod, double atrMultiplier, boolean useAtrStop) {
        this.stopLossPercent = stopLossPercent;
        this.takeProfitHalf  = takeProfitHalf;
        this.takeProfitFull  = takeProfitFull;
        this.doTProfit       = doTProfit;
        this.maxPositionRatio = maxPositionRatio;
        this.atrPeriod       = atrPeriod;
        this.atrMultiplier   = atrMultiplier;
        this.useAtrStop      = useAtrStop;
    }

    @Override
    public String getStrategyName() { return "短线做T策略"; }

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
        return String.format("短线做T：%s  减仓止盈%.0f%%  清仓止盈%.0f%%  做T触发%.0f%%",
                stopDesc, takeProfitHalf * 100, takeProfitFull * 100, doTProfit * 100);
    }

    @Override
    public int getMinBarsRequired() { return 30; }

    @Override
    public TradeSignal generateSignal(String stockCode, String stockName,
                                       List<StockBar> bars, AnalysisResult analysis,
                                       Portfolio portfolio) {
        if (!isApplicable(bars)) {
            return holdSignal(stockCode, stockName, "K线数据不足");
        }

        double[] closes = TechnicalIndicator.extractCloses(bars);
        double currentPrice = closes[closes.length - 1];
        LocalTime now = LocalTime.now();
        boolean hasPosition  = portfolio != null && portfolio.hasPosition(stockCode);
        boolean hasAvailable = hasPosition &&
                portfolio.getPosition(stockCode).getAvailableQuantity() > 0;

        // ===== 已持仓：止损/止盈 =====
        if (hasPosition) {
            Position pos = portfolio.getPosition(stockCode);
            double avgCost    = pos.getAvgCost();
            double profitRate = avgCost > 0 ? (currentPrice - avgCost) / avgCost : 0;

            // ==== 计算 ATR 动态止损价 ====
            double effectiveStopPrice;
            String stopDesc;
            if (useAtrStop && bars.size() >= atrPeriod + 1) {
                // 短线：直接用入场价 - N×ATR（不用吊灯，做T不需要跟踪最高价）
                // [P1-2] 使用自适应ATR倍数：低波动用2.5倍，高波动用1.2倍
                double adaptiveMultiplier = adaptiveAtrMultiplier(bars);
                double atrStopPrice = AtrStopLoss.calcStopPrice(bars, avgCost, atrPeriod, adaptiveMultiplier);
                double fixedStopPrice = avgCost * (1 - stopLossPercent);
                // 取较高者（止损更紧）
                effectiveStopPrice = Math.max(atrStopPrice, fixedStopPrice);
                double atrPct = AtrStopLoss.atrPercent(bars, atrPeriod) * 100;
                stopDesc = String.format("ATR动态止损=%.2f（ATR=%.1f%%×%.1f自适应，ATR止损=%.2f，固定=%.2f）",
                        effectiveStopPrice, atrPct, adaptiveMultiplier, atrStopPrice, fixedStopPrice);
            } else {
                effectiveStopPrice = avgCost * (1 - stopLossPercent);
                stopDesc = String.format("固定止损=%.2f（%.0f%%）", effectiveStopPrice, stopLossPercent * 100);
            }

            // 1. 止损（无视T+1，只要能用就执行）
            if (hasAvailable && currentPrice <= effectiveStopPrice) {
                return TradeSignal.builder()
                        .signalId(UUID.randomUUID().toString())
                        .stockCode(stockCode).stockName(stockName)
                        .signalType(TradeSignal.SignalType.STOP_LOSS).strength(100)
                        .suggestedPrice(currentPrice).strategyName(getStrategyName())
                        .signalTime(LocalDateTime.now())
                        .reason(String.format("短线止损：成本=%.2f 现价=%.2f 亏损=%.2f%%  %s",
                                avgCost, currentPrice, profitRate * 100, stopDesc))
                        .build();
            }

            if (hasAvailable) {
                TechnicalIndicator.MACDResult macd = TechnicalIndicator.macd(closes);

                // ==== [P3-2 优化] 周五强制减仓短线仓位 ====
                // 背景：历史数据显示，持有短线仓位越过周末风险极大：
                //   - 周末有重大新闻/政策出台的概率高，且无法止损
                //   - 历史上周五收盘持仓，次周一开盘低开的比例明显高于普通隔夜
                // 策略：
                //   - 周五 14:00 之后：
                //     * 浮盈 > 0（盈利）：清仓锁利，不留隔夜风险
                //     * 浮亏 < -1%（亏损明显）：清仓止损，不扛周末
                //     * 微亏（0% ~ -1%）：减仓70%，保留少量仓位观察下周开盘
                // 豁免：中长期策略（minHoldDays>0）不受此逻辑约束
                boolean isFriday = LocalDate.now().getDayOfWeek() == DayOfWeek.FRIDAY;
                boolean isFridayAfternoon = isFriday && now.isAfter(LocalTime.of(14, 0));
                if (isFridayAfternoon) {
                    if (profitRate > 0) {
                        return TradeSignal.builder()
                                .signalId(UUID.randomUUID().toString())
                                .stockCode(stockCode).stockName(stockName)
                                .signalType(TradeSignal.SignalType.TAKE_PROFIT).strength(92)
                                .suggestedPrice(currentPrice).strategyName(getStrategyName())
                                .signalTime(LocalDateTime.now())
                                .reason(String.format("[P3-2]周五清仓锁利：盈利=%.2f%%，周末风险高，不留隔夜仓位", profitRate * 100))
                                .build();
                    } else if (profitRate < -0.01) {
                        return TradeSignal.builder()
                                .signalId(UUID.randomUUID().toString())
                                .stockCode(stockCode).stockName(stockName)
                                .signalType(TradeSignal.SignalType.STOP_LOSS).strength(90)
                                .suggestedPrice(currentPrice).strategyName(getStrategyName())
                                .signalTime(LocalDateTime.now())
                                .reason(String.format("[P3-2]周五止损清仓：亏损=%.2f%%（<-1%%），不扛周末风险", profitRate * 100))
                                .build();
                    } else {
                        // 微亏（-1%~0%）：减仓70%，保留少量仓位观察开盘
                        return TradeSignal.builder()
                                .signalId(UUID.randomUUID().toString())
                                .stockCode(stockCode).stockName(stockName)
                                .signalType(TradeSignal.SignalType.SELL).strength(75)
                                .suggestedPrice(currentPrice).strategyName(getStrategyName())
                                .signalTime(LocalDateTime.now())
                                .reason(String.format("[P3-2]周五减仓70%%：微亏=%.2f%%（-1%%~0%%），减少周末风险敞口", profitRate * 100))
                                .build();
                    }
                }

                // ==== 计算 ATR 动态止盈目标 ====
                // 根据该股票近期波动率（ATR%）自适应调整止盈线：
                //   高波动票 → 目标上调（顺势拿住利润），低波动票 → 目标下调（快速锁利）
                double dynTakeProfitFull = AtrStopLoss.dynamicTakeProfitRate(bars, atrPeriod, takeProfitFull);
                double dynTakeProfitHalf = AtrStopLoss.dynamicTakeProfitRate(bars, atrPeriod, takeProfitHalf);
                String tpDesc = AtrStopLoss.dynamicTakeProfitDesc(bars, atrPeriod, takeProfitFull);

                // 2. 清仓止盈
                if (profitRate >= dynTakeProfitFull) {
                    return TradeSignal.builder()
                            .signalId(UUID.randomUUID().toString())
                            .stockCode(stockCode).stockName(stockName)
                            .signalType(TradeSignal.SignalType.TAKE_PROFIT).strength(95)
                            .suggestedPrice(currentPrice).strategyName(getStrategyName())
                            .signalTime(LocalDateTime.now())
                            .reason(String.format("短线清仓止盈：盈利=%.2f%%（动态目标=%.1f%%）  %s",
                                    profitRate * 100, dynTakeProfitFull * 100, tpDesc))
                            .build();
                }

                // 3. 减仓止盈
                if (profitRate >= dynTakeProfitHalf) {
                    return TradeSignal.builder()
                            .signalId(UUID.randomUUID().toString())
                            .stockCode(stockCode).stockName(stockName)
                            .signalType(TradeSignal.SignalType.TAKE_PROFIT).strength(85)
                            .suggestedPrice(currentPrice).strategyName(getStrategyName())
                            .signalTime(LocalDateTime.now())
                            .reason(String.format("减仓锁利做T：盈利=%.2f%%（动态减仓线=%.1f%%），卖出50%%仓位  %s",
                                    profitRate * 100, dynTakeProfitHalf * 100,
                                    AtrStopLoss.dynamicTakeProfitDesc(bars, atrPeriod, takeProfitHalf)))
                            .build();
                }

                // 4. 日内高点做T（依据 IC 检验：MACD死叉/均线走弱为主要卖出信号；RSI/KDJ仅作辅助）
                if (profitRate >= doTProfit) {
                    List<String> sellReasons = new ArrayList<>();
                    double rsi6 = analysis != null ? analysis.getRsi6() : 50;
                    TechnicalIndicator.KDJResult kdj = TechnicalIndicator.kdj(bars);
                    double sellScore = 0.0;
                    // [强卖信号] MACD死叉（IC中等有效，方向明确）
                    if (macd.isDeathCross()) { sellReasons.add("MACD死叉"); sellScore += 0.8; }
                    // [强卖信号] 均线走弱（IC最强因子反向信号）
                    if (analysis != null && currentPrice < analysis.getMa5()
                            && analysis.getMa5() < analysis.getMa10()) {
                        sellReasons.add("跌破MA5均线走弱"); sellScore += 1.0;
                    }
                    // [弱卖信号] RSI超买（IC弱，作为辅助；不单独触发卖出）
                    if (rsi6 > 70) { sellReasons.add(String.format("RSI超买=%.1f", rsi6)); sellScore += 0.4; }
                    // [弱卖信号] KDJ顶部死叉（IC弱/反向，仅极值区有效）
                    if (kdj.isDeathCross() && kdj.isOverbought()) { sellReasons.add("KDJ顶部死叉"); sellScore += 0.3; }
                    // 量比不再作为卖出触发条件（IC检验无效）
                    // 触发条件：sellScore >= 0.8（至少一个有效信号）
                    if (sellScore >= 0.8 && !sellReasons.isEmpty()) {
                        return TradeSignal.builder()
                                .signalId(UUID.randomUUID().toString())
                                .stockCode(stockCode).stockName(stockName)
                                .signalType(TradeSignal.SignalType.SELL).strength(80)
                                .suggestedPrice(currentPrice).strategyName(getStrategyName())
                                .signalTime(LocalDateTime.now())
                                .reason(String.format("做T减仓（盈利%.2f%%）：%s",
                                        profitRate * 100, String.join("，", sellReasons)))
                                .build();
                    }
                }

                // 5. 尾盘策略（14:45后，根据盈利档位和技术面决定操作，减少隔夜风险）
                if (now.isAfter(NO_NEW_POSITION_AFTER)) {
                    // [P1-1 优化] 技术面恶化检测：评分低或趋势转弱时，不管盈亏都应减仓
                    // 背景：历史数据显示「技术面恶化后继续持股隔夜」是亏损的主要来源之一，
                    //   即使当前微亏（-1%~0%），若评分低于55分或趋势已转弱，次日开盘大概率继续下跌。
                    // 规则：
                    //   1. 综合评分 < 55 → 技术面明显偏弱，无论盈亏减仓70%（不清仓是因为可能短暂弱）
                    //   2. 趋势 = DOWN/STRONG_DOWN → 趋势已破位，无论盈亏清仓
                    //   3. 以上均优先于盈亏判断，盈利保护逻辑在后
                    int currentScore = analysis != null ? analysis.getOverallScore() : 50;
                    AnalysisResult.TrendDirection currentTrend = analysis != null
                            ? analysis.getTrend() : AnalysisResult.TrendDirection.SIDEWAYS;
                    boolean trendBearish = currentTrend == AnalysisResult.TrendDirection.DOWN
                            || currentTrend == AnalysisResult.TrendDirection.STRONG_DOWN;
                    boolean scoreWeak = currentScore < 55;

                    if (trendBearish) {
                        // 趋势破位：尾盘清仓，无论盈亏；盈亏决定信号类型（影响报表统计准确性）
                        TradeSignal.SignalType exitType = profitRate >= 0
                                ? TradeSignal.SignalType.TAKE_PROFIT
                                : TradeSignal.SignalType.STOP_LOSS;
                        return TradeSignal.builder()
                                .signalId(UUID.randomUUID().toString())
                                .stockCode(stockCode).stockName(stockName)
                                .signalType(exitType).strength(88)
                                .suggestedPrice(currentPrice).strategyName(getStrategyName())
                                .signalTime(LocalDateTime.now())
                                .reason(String.format("[P1-1]尾盘清仓（技术面破位）：评分=%d 趋势=%s 当前盈亏=%.2f%%，清仓规避隔夜风险",
                                        currentScore, currentTrend.getDescription(), profitRate * 100))
                                .build();
                    } else if (scoreWeak) {
                        // 评分偏低：尾盘减仓70%，降低隔夜风险敞口
                        return TradeSignal.builder()
                                .signalId(UUID.randomUUID().toString())
                                .stockCode(stockCode).stockName(stockName)
                                .signalType(TradeSignal.SignalType.SELL).strength(72)
                                .suggestedPrice(currentPrice).strategyName(getStrategyName())
                                .signalTime(LocalDateTime.now())
                                .reason(String.format("[P1-1]尾盘减仓70%%（评分偏低）：评分=%d（<55）当前盈亏=%.2f%%，降低隔夜风险",
                                        currentScore, profitRate * 100))
                                .build();
                    }

                    if (profitRate >= 0.03) {
                        // 盈利>=3%：尾盘清仓，当日锁定收益，不留隔夜风险
                        return TradeSignal.builder()
                                .signalId(UUID.randomUUID().toString())
                                .stockCode(stockCode).stockName(stockName)
                                .signalType(TradeSignal.SignalType.TAKE_PROFIT).strength(90)
                                .suggestedPrice(currentPrice).strategyName(getStrategyName())
                                .signalTime(LocalDateTime.now())
                                .reason(String.format("尾盘清仓锁利：盈利=%.2f%%（>=3%%），清仓规避隔夜风险", profitRate * 100))
                                .build();
                    } else if (profitRate >= 0.01) {
                        // 盈利1%~3%：减仓70%，减少隔夜敞口但保留少量仓位
                        return TradeSignal.builder()
                                .signalId(UUID.randomUUID().toString())
                                .stockCode(stockCode).stockName(stockName)
                                .signalType(TradeSignal.SignalType.SELL).strength(70)
                                .suggestedPrice(currentPrice).strategyName(getStrategyName())
                                .signalTime(LocalDateTime.now())
                                .reason(String.format("尾盘减仓70%%：盈利=%.2f%%（1%%~3%%），减少隔夜敞口", profitRate * 100))
                                .build();
                    }
                    // 盈利<1% 且技术面正常：不操作（收益太少，手续费吃掉）
                }
            }
        }

        // ===== 买入逻辑 =====
        if (now.isAfter(NO_NEW_POSITION_AFTER) || now.isBefore(MARKET_OPEN)) {
            return holdSignal(stockCode, stockName, "尾盘/开盘前，不新建仓");
        }
        if (hasPosition) {
            Position pos = portfolio.getPosition(stockCode);
            if (pos.getLastBuyDate() != null && pos.getLastBuyDate().equals(LocalDate.now())) {
                return holdSignal(stockCode, stockName, "今日已买入，等待次日做T");
            }
        }

        TechnicalIndicator.MACDResult macd = TechnicalIndicator.macd(closes);
        TechnicalIndicator.KDJResult kdj = TechnicalIndicator.kdj(bars);
        double rsi6 = analysis != null ? analysis.getRsi6() : 50;
        // 动量因子（IC检验有效因子，替代无效的量比信号）
        int n = closes.length;
        double momentum5  = (n >= 6  && closes[n - 6]  > 0) ? (closes[n - 1] - closes[n - 6])  / closes[n - 6]  : 0.0;
        double momentum20 = (n >= 21 && closes[n - 21] > 0) ? (closes[n - 1] - closes[n - 21]) / closes[n - 21] : 0.0;

        // ===== 买入过滤：当日实时价低于昨收 -2%，说明日内已破位，不买入 =====
        // closes[n-1] 是昨收（日线），currentPrice 是追加的实时行情
        // 当 bars 中已追加实时Bar时，closes 最后一个仍为昨收，currentPrice 来自实时
        double prevClose = closes[n - 1];  // 最新日线收盘价（昨收）
        if (prevClose > 0 && currentPrice < prevClose * 0.98) {
            return holdSignal(stockCode, stockName,
                    String.format("日内破位过滤：实时价=%.2f 低于昨收%.2f的98%%（=%.2f），日线虽强但日内已走弱，不买入",
                            currentPrice, prevClose, prevClose * 0.98));
        }

        // ===== 买入信号积累（依据 IC 检验结论重新设计权重）=====
        // 规则：每个信号有"分值"，总分 >= 2.0 才买入（替代简单信号计数）
        // 各信号分值依据 IC 检验中对应因子的有效性设定：
        //   MA多头（最强 IC）      → 分值 1.0（锚点）
        //   MACD信号（中等 IC）    → 分值 0.8
        //   20日动量（强 IC）      → 分值 0.9
        //   5日动量（中等 IC）     → 分值 0.6
        //   RSI超卖（弱 IC）       → 分值 0.5（仅超卖区有效）
        //   KDJ超卖（弱/反向 IC）  → 分值 0.4（仅极值区有参考）
        //   量比（IC无效）         → 移除，不再作为买入条件
        List<String> buyReasons = new ArrayList<>();
        double buyScore = 0.0;

        // [强信号] MA多头排列（IC最强因子）
        if (analysis != null && currentPrice > analysis.getMa5() &&
                TechnicalIndicator.isBullishAlignment(
                        analysis.getMa5(), analysis.getMa10(),
                        analysis.getMa20(), analysis.getMa60())) {
            buyReasons.add("均线多头排列");
            buyScore += 1.0;
        } else if (analysis != null && currentPrice > analysis.getMa5()
                && analysis.getMa5() > analysis.getMa10()) {
            buyReasons.add("MA5>MA10向上");
            buyScore += 0.5;  // 部分多头
        }

        // [强信号] 20日动量（中期趋势强，IC有效）
        if (momentum20 > 0.08) {
            buyReasons.add(String.format("20日动量强势=+%.1f%%", momentum20 * 100));
            buyScore += 0.9;
        } else if (momentum20 > 0.03) {
            buyReasons.add(String.format("20日动量上行=+%.1f%%", momentum20 * 100));
            buyScore += 0.5;
        }

        // [中信号] MACD（IC中等有效）
        if (macd.isGoldenCross()) {
            buyReasons.add("MACD金叉");
            buyScore += 0.8;
        } else if (macd.latestDif() > 0 && macd.latestMacd() > 0) {
            buyReasons.add("MACD0轴上方且柱体为正");
            buyScore += 0.6;
        } else if (macd.latestDif() > 0) {
            buyReasons.add("MACD0轴上方");
            buyScore += 0.3;
        }

        // [中信号] 5日动量（IC中等，但短期有均值回归，适当降权）
        if (momentum5 > 0.03 && momentum5 < 0.08) {
            // 注意：momentum5 过大（>8%）可能已经追高，不算正信号
            buyReasons.add(String.format("5日健康上涨=+%.1f%%", momentum5 * 100));
            buyScore += 0.6;
        }

        // [弱信号] RSI超卖（IC弱，仅超卖有少量反弹预测力）
        if (rsi6 < 30) {
            buyReasons.add(String.format("RSI超卖回升=%.1f", rsi6));
            buyScore += 0.5;
        } else if (rsi6 >= 30 && rsi6 <= 50) {
            buyReasons.add(String.format("RSI健康区间=%.1f", rsi6));
            buyScore += 0.2;  // 健康区间为弱正信号
        }

        // [弱信号] KDJ超卖（IC弱/方向反向，仅极值区有参考价值）
        if (kdj.isGoldenCross() && kdj.isOversold()) {
            buyReasons.add("KDJ超卖金叉");
            buyScore += 0.4;
        } else if (kdj.isOversold()) {
            buyReasons.add("KDJ超卖区");
            buyScore += 0.2;
        }
        // 注意：量比信号已移除（IC检验无效，VOL_RATIO |IC均值| < 0.02）

        int score = analysis != null ? analysis.getOverallScore() : 0;
        // 买入门槛：综合评分>=60 且 IC加权信号分值>=2.0；或者强势突破（评分>=72 且信号分>=1.5）
        boolean canBuy = score >= 60 && buyScore >= 2.0;
        canBuy = canBuy || (score >= 72 && buyScore >= 1.5);

        if (canBuy) {
            // 强度基于 IC 加权信号分值（buyScore）和综合评分
            // buyScore 满分约 3.6（所有信号同时触发），归一化到 50-92 区间
            int strength = Math.min((int)(50 + buyScore * 10 + Math.max(0, score - 60) * 0.5), 92);
            double posRatio = strength >= 80 ? maxPositionRatio
                    : (strength >= 70 ? maxPositionRatio * 0.7 : maxPositionRatio * 0.5);

            // ATR 初始止损价 [P1-2] 使用自适应ATR倍数
            double adaptiveMultiplierForBuy = adaptiveAtrMultiplier(bars);
            double atrStopPrice = (useAtrStop && bars.size() >= atrPeriod + 1)
                    ? AtrStopLoss.calcStopPrice(bars, currentPrice, atrPeriod, adaptiveMultiplierForBuy)
                    : currentPrice * (1 - stopLossPercent);
            String stopLabel = useAtrStop
                    ? String.format("ATR×%.1f自适应止损=%.2f", adaptiveMultiplierForBuy, atrStopPrice)
                    : String.format("固定%.0f%%止损=%.2f", stopLossPercent * 100, atrStopPrice);

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
                    .reason(String.format("短线买入：%s  %s",
                            String.join("，", buyReasons), stopLabel))
                    .build();
        }

        return holdSignal(stockCode, stockName,
                String.format("条件不足（评分%d，IC加权信号分=%.1f，需>=2.0）", score, buyScore));
    }

    @Override
    public double calculatePositionSize(TradeSignal signal, Portfolio portfolio) {
        if (signal.getStrength() >= 80) return maxPositionRatio;
        if (signal.getStrength() >= 70) return maxPositionRatio * 0.7;
        return maxPositionRatio * 0.5;
    }

    /**
     * [P1-2 优化] 根据 ATR% 动态选择 ATR 倍数
     * <p>
     * 背景：固定 ATR 倍数对不同波动率股票效果差异极大：
     *   - 低波动股（ATR%<1%，如银行/公用事业）：止损太宽 → 1.5倍ATR可能离入场价仅0.5%，不起作用
     *   - 高波动股（ATR%>3%，如题材小盘股）：止损太宽 → 1.5倍ATR即达5%，会被正常波动洗出
     * <p>
     * 差异化倍数策略：
     *   - ATR% <= 1.0%（低波动）：用 2.5 倍，给止损更多空间，避免被正常回调洗出
     *   - ATR% 1.0%~2.5%（中等波动）：用配置的 atrMultiplier（默认1.5倍）
     *   - ATR% > 2.5%（高波动）：用 1.2 倍，收紧止损，防止高波动股单笔亏损过大
     *
     * @param bars K线数据
     * @return 适合当前股票波动率的 ATR 倍数
     */
    private double adaptiveAtrMultiplier(List<StockBar> bars) {
        if (!useAtrStop || bars == null || bars.size() < atrPeriod) return atrMultiplier;
        double atrPct = AtrStopLoss.atrPercent(bars, atrPeriod) * 100;
        if (atrPct <= 0) return atrMultiplier;
        if (atrPct <= 1.0) return 2.5;        // 低波动：宽止损
        if (atrPct <= 2.5) return atrMultiplier; // 中波动：默认
        return 1.2;                              // 高波动：紧止损
    }

    private TradeSignal holdSignal(String code, String name, String reason) {
        return TradeSignal.builder()
                .signalId(UUID.randomUUID().toString())
                .stockCode(code).stockName(name)
                .signalType(TradeSignal.SignalType.HOLD).strength(0)
                .strategyName(getStrategyName())
                .signalTime(LocalDateTime.now()).reason(reason)
                .build();
    }
}


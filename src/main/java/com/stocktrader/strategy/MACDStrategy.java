package com.stocktrader.strategy;

import com.stocktrader.analysis.TechnicalIndicator;
import com.stocktrader.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * MACD金叉/死叉策略
 * <p>
 * 买入条件：
 * 1. MACD发生金叉（DIF上穿DEA）
 * 2. DIF和DEA均在0轴附近或0轴以上（排除深度空头中的反弹）
 * 3. 成交量放大
 * <p>
 * 卖出条件：
 * 1. MACD发生死叉（DIF下穿DEA）
 * 2. 或者达到止损/止盈价位
 */
public class MACDStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(MACDStrategy.class);

    // 策略参数
    private final int fastPeriod;
    private final int slowPeriod;
    private final int signalPeriod;
    private final double stopLossPercent;   // 止损比例（如0.05表示亏损5%止损）
    private final double takeProfitPercent; // 止盈比例（如0.15表示盈利15%止盈）
    private final double maxPositionRatio;  // 最大仓位比例

    public MACDStrategy() {
        this(12, 26, 9, 0.05, 0.15, 0.3);
    }

    public MACDStrategy(int fastPeriod, int slowPeriod, int signalPeriod,
                        double stopLossPercent, double takeProfitPercent, double maxPositionRatio) {
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
        this.signalPeriod = signalPeriod;
        this.stopLossPercent = stopLossPercent;
        this.takeProfitPercent = takeProfitPercent;
        this.maxPositionRatio = maxPositionRatio;
    }

    @Override
    public String getStrategyName() {
        return "MACD金叉策略";
    }

    @Override
    public String getDescription() {
        return String.format("MACD(%d,%d,%d)金叉买入死叉卖出，止损%.0f%%止盈%.0f%%",
                fastPeriod, slowPeriod, signalPeriod,
                stopLossPercent * 100, takeProfitPercent * 100);
    }

    @Override
    public int getMinBarsRequired() {
        return slowPeriod + signalPeriod + 10;
    }

    @Override
    public TradeSignal generateSignal(String stockCode, String stockName,
                                       List<StockBar> bars, AnalysisResult analysis,
                                       Portfolio portfolio) {
        if (!isApplicable(bars)) {
            return buildHoldSignal(stockCode, stockName, "数据不足");
        }

        double[] closes = TechnicalIndicator.extractCloses(bars);
        TechnicalIndicator.MACDResult macd = TechnicalIndicator.macd(closes, fastPeriod, slowPeriod, signalPeriod);
        double currentPrice = closes[closes.length - 1];

        // 检查是否已持仓
        boolean hasPosition = portfolio != null && portfolio.hasPosition(stockCode);

        // === 已持仓：检查止损/止盈/死叉 ===
        if (hasPosition) {
            Position position = portfolio.getPosition(stockCode);
            double avgCost = position.getAvgCost();
            double profitRate = (currentPrice - avgCost) / avgCost;

            // 止损
            if (profitRate < -stopLossPercent) {
                return TradeSignal.builder()
                        .signalId(UUID.randomUUID().toString())
                        .stockCode(stockCode)
                        .stockName(stockName)
                        .signalType(TradeSignal.SignalType.STOP_LOSS)
                        .strength(90)
                        .suggestedPrice(currentPrice)
                        .strategyName(getStrategyName())
                        .signalTime(LocalDateTime.now())
                        .reason(String.format("触发止损：成本%.2f 当前%.2f 亏损%.1f%%",
                                avgCost, currentPrice, profitRate * 100))
                        .build();
            }

            // 止盈
            if (profitRate > takeProfitPercent) {
                return TradeSignal.builder()
                        .signalId(UUID.randomUUID().toString())
                        .stockCode(stockCode)
                        .stockName(stockName)
                        .signalType(TradeSignal.SignalType.TAKE_PROFIT)
                        .strength(80)
                        .suggestedPrice(currentPrice)
                        .strategyName(getStrategyName())
                        .signalTime(LocalDateTime.now())
                        .reason(String.format("触发止盈：成本%.2f 当前%.2f 盈利%.1f%%",
                                avgCost, currentPrice, profitRate * 100))
                        .build();
            }

            // MACD死叉卖出
            if (macd.isDeathCross()) {
                return TradeSignal.builder()
                        .signalId(UUID.randomUUID().toString())
                        .stockCode(stockCode)
                        .stockName(stockName)
                        .signalType(TradeSignal.SignalType.SELL)
                        .strength(70)
                        .suggestedPrice(currentPrice)
                        .strategyName(getStrategyName())
                        .signalTime(LocalDateTime.now())
                        .reason("MACD死叉，卖出信号")
                        .build();
            }
        }

        // === 未持仓：寻找买入机会 ===
        if (!hasPosition) {
            boolean goldenCross = macd.isGoldenCross();
            double dif = macd.latestDif();
            double dea = macd.latestDea();

            if (goldenCross && dif > -0.5 && dea > -0.5) {
                // 判断成交量
                double volRatio = TechnicalIndicator.volumeRatio(bars, 5);
                boolean volOk = volRatio >= 1.0;

                if (volOk) {
                    int strength = 60;
                    if (dif > 0 && dea > 0) strength += 15;  // 0轴以上金叉更可靠
                    if (volRatio > 1.5) strength += 10;

                    double stopLoss = currentPrice * (1 - stopLossPercent);
                    double takeProfit = currentPrice * (1 + takeProfitPercent);

                    return TradeSignal.builder()
                            .signalId(UUID.randomUUID().toString())
                            .stockCode(stockCode)
                            .stockName(stockName)
                            .signalType(strength >= 75 ? TradeSignal.SignalType.STRONG_BUY : TradeSignal.SignalType.BUY)
                            .strength(strength)
                            .suggestedPrice(currentPrice)
                            .positionRatio(maxPositionRatio)
                            .stopLossPrice(stopLoss)
                            .takeProfitPrice(takeProfit)
                            .strategyName(getStrategyName())
                            .signalTime(LocalDateTime.now())
                            .reason(String.format("MACD金叉，DIF=%.4f DEA=%.4f 量比=%.2f",
                                    dif, dea, volRatio))
                            .build();
                }
            }
        }

        return buildHoldSignal(stockCode, stockName, "无明确信号");
    }

    @Override
    public double calculatePositionSize(TradeSignal signal, Portfolio portfolio) {
        if (portfolio == null) return maxPositionRatio;

        // 根据信号强度调整仓位
        double baseRatio = maxPositionRatio;
        if (signal.getStrength() >= 80) {
            baseRatio = maxPositionRatio;
        } else if (signal.getStrength() >= 60) {
            baseRatio = maxPositionRatio * 0.7;
        } else {
            baseRatio = maxPositionRatio * 0.5;
        }

        return baseRatio;
    }

    private TradeSignal buildHoldSignal(String stockCode, String stockName, String reason) {
        return TradeSignal.builder()
                .signalId(UUID.randomUUID().toString())
                .stockCode(stockCode)
                .stockName(stockName)
                .signalType(TradeSignal.SignalType.HOLD)
                .strength(0)
                .strategyName(getStrategyName())
                .signalTime(LocalDateTime.now())
                .reason(reason)
                .build();
    }
}


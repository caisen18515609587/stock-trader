package com.stocktrader.strategy;

import com.stocktrader.analysis.TechnicalIndicator;
import com.stocktrader.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * RSI超买超卖策略
 * <p>
 * 买入条件：RSI6跌破30（超卖区），同时KDJ J值也低于20
 * 卖出条件：RSI6突破70（超买区），同时KDJ J值也高于80
 */
public class RSIStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(RSIStrategy.class);

    private final int rsiPeriod;
    private final double oversoldLevel;
    private final double overboughtLevel;
    private final double stopLossPercent;
    private final double takeProfitPercent;
    private final double maxPositionRatio;

    public RSIStrategy() {
        this(6, 30.0, 70.0, 0.06, 0.12, 0.25);
    }

    public RSIStrategy(int rsiPeriod, double oversoldLevel, double overboughtLevel,
                       double stopLossPercent, double takeProfitPercent, double maxPositionRatio) {
        this.rsiPeriod = rsiPeriod;
        this.oversoldLevel = oversoldLevel;
        this.overboughtLevel = overboughtLevel;
        this.stopLossPercent = stopLossPercent;
        this.takeProfitPercent = takeProfitPercent;
        this.maxPositionRatio = maxPositionRatio;
    }

    @Override
    public String getStrategyName() {
        return "RSI超买超卖策略";
    }

    @Override
    public String getDescription() {
        return String.format("RSI%d: 超卖%.0f买入/超买%.0f卖出，止损%.0f%%止盈%.0f%%",
                rsiPeriod, oversoldLevel, overboughtLevel,
                stopLossPercent * 100, takeProfitPercent * 100);
    }

    @Override
    public int getMinBarsRequired() {
        return rsiPeriod + 20;
    }

    @Override
    public TradeSignal generateSignal(String stockCode, String stockName,
                                       List<StockBar> bars, AnalysisResult analysis,
                                       Portfolio portfolio) {
        if (!isApplicable(bars)) {
            return buildHoldSignal(stockCode, stockName, "数据不足");
        }

        double[] closes = TechnicalIndicator.extractCloses(bars);
        double[] rsiArr = TechnicalIndicator.rsiArray(closes, rsiPeriod);
        TechnicalIndicator.KDJResult kdj = TechnicalIndicator.kdj(bars);

        double currentPrice = closes[closes.length - 1];
        double rsi = TechnicalIndicator.lastValid(rsiArr);
        double rsiPrev = rsiArr.length >= 2 ? rsiArr[rsiArr.length - 2] : rsi;

        boolean hasPosition = portfolio != null && portfolio.hasPosition(stockCode);

        // 止损/止盈检查（已持仓）
        if (hasPosition) {
            Position position = portfolio.getPosition(stockCode);
            double avgCost = position.getAvgCost();
            double profitRate = (currentPrice - avgCost) / avgCost;

            if (profitRate < -stopLossPercent) {
                return TradeSignal.builder()
                        .signalId(UUID.randomUUID().toString())
                        .stockCode(stockCode).stockName(stockName)
                        .signalType(TradeSignal.SignalType.STOP_LOSS).strength(95)
                        .suggestedPrice(currentPrice).strategyName(getStrategyName())
                        .signalTime(LocalDateTime.now())
                        .reason(String.format("止损：亏损%.1f%%", profitRate * 100))
                        .build();
            }

            if (profitRate > takeProfitPercent || rsi > overboughtLevel) {
                return TradeSignal.builder()
                        .signalId(UUID.randomUUID().toString())
                        .stockCode(stockCode).stockName(stockName)
                        .signalType(TradeSignal.SignalType.SELL).strength(75)
                        .suggestedPrice(currentPrice).strategyName(getStrategyName())
                        .signalTime(LocalDateTime.now())
                        .reason(String.format("RSI超买=%.1f 或达止盈", rsi))
                        .build();
            }
        }

        // 买入信号（未持仓）：RSI由超卖区向上回升
        if (!hasPosition && rsi < oversoldLevel && rsiPrev >= rsi) {
            // RSI进入超卖且KDJ也超卖，信号更可靠
            boolean kdjOversold = kdj.isOversold();
            int strength = 65 + (kdjOversold ? 15 : 0) + (rsi < 20 ? 10 : 0);

            return TradeSignal.builder()
                    .signalId(UUID.randomUUID().toString())
                    .stockCode(stockCode).stockName(stockName)
                    .signalType(TradeSignal.SignalType.BUY).strength(strength)
                    .suggestedPrice(currentPrice)
                    .positionRatio(maxPositionRatio)
                    .stopLossPrice(currentPrice * (1 - stopLossPercent))
                    .takeProfitPrice(currentPrice * (1 + takeProfitPercent))
                    .strategyName(getStrategyName())
                    .signalTime(LocalDateTime.now())
                    .reason(String.format("RSI超卖=%.1f%s", rsi, kdjOversold ? " KDJ也超卖" : ""))
                    .build();
        }

        return buildHoldSignal(stockCode, stockName, String.format("RSI=%.1f 无明确信号", rsi));
    }

    @Override
    public double calculatePositionSize(TradeSignal signal, Portfolio portfolio) {
        return signal.getStrength() >= 75 ? maxPositionRatio : maxPositionRatio * 0.6;
    }

    private TradeSignal buildHoldSignal(String stockCode, String stockName, String reason) {
        return TradeSignal.builder()
                .signalId(UUID.randomUUID().toString())
                .stockCode(stockCode).stockName(stockName)
                .signalType(TradeSignal.SignalType.HOLD).strength(0)
                .strategyName(getStrategyName())
                .signalTime(LocalDateTime.now()).reason(reason)
                .build();
    }
}


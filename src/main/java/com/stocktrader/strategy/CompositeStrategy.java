package com.stocktrader.strategy;

import com.stocktrader.analysis.StockAnalyzer;
import com.stocktrader.analysis.TechnicalIndicator;
import com.stocktrader.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 综合多因子策略（推荐使用）
 * 整合MACD、RSI、KDJ、均线、布林带等多个指标，通过综合评分系统生成可靠的交易信号。
 */
public class CompositeStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(CompositeStrategy.class);

    private final StockAnalyzer stockAnalyzer;
    private final double stopLossPercent;
    private final double takeProfitPercent;
    private final double maxPositionRatio;
    private final int minBuyConditions;
    private final int minSellConditions;

    public CompositeStrategy() {
        this(0.07, 0.20, 0.30, 2, 2);
    }

    public CompositeStrategy(double stopLossPercent, double takeProfitPercent,
                              double maxPositionRatio, int minBuyConditions, int minSellConditions) {
        this.stockAnalyzer = new StockAnalyzer();
        this.stopLossPercent = stopLossPercent;
        this.takeProfitPercent = takeProfitPercent;
        this.maxPositionRatio = maxPositionRatio;
        this.minBuyConditions = minBuyConditions;
        this.minSellConditions = minSellConditions;
    }

    @Override
    public String getStrategyName() {
        return "综合多因子策略";
    }

    @Override
    public String getDescription() {
        return String.format("多因子综合评分策略，止损%.0f%%止盈%.0f%%，最大仓位%.0f%%",
                stopLossPercent * 100, takeProfitPercent * 100, maxPositionRatio * 100);
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
            return buildHoldSignal(stockCode, stockName, "K线数据不足60根");
        }

        if (analysis == null) {
            analysis = stockAnalyzer.analyze(stockCode, stockName, bars);
        }

        double[] closes = TechnicalIndicator.extractCloses(bars);
        double currentPrice = closes[closes.length - 1];
        boolean hasPosition = portfolio != null && portfolio.hasPosition(stockCode);

        // ====== 止损/止盈检查 ======
        if (hasPosition) {
            Position position = portfolio.getPosition(stockCode);
            double avgCost = position.getAvgCost();
            double profitRate = (currentPrice - avgCost) / avgCost;

            if (profitRate < -stopLossPercent) {
                return TradeSignal.builder()
                        .signalId(UUID.randomUUID().toString())
                        .stockCode(stockCode).stockName(stockName)
                        .signalType(TradeSignal.SignalType.STOP_LOSS).strength(100)
                        .suggestedPrice(currentPrice).strategyName(getStrategyName())
                        .signalTime(LocalDateTime.now())
                        .reason(String.format("硬止损：成本%.2f，当前%.2f，亏损%.1f%%",
                                avgCost, currentPrice, profitRate * 100))
                        .build();
            }

            if (profitRate > takeProfitPercent) {
                return TradeSignal.builder()
                        .signalId(UUID.randomUUID().toString())
                        .stockCode(stockCode).stockName(stockName)
                        .signalType(TradeSignal.SignalType.TAKE_PROFIT).strength(85)
                        .suggestedPrice(currentPrice).strategyName(getStrategyName())
                        .signalTime(LocalDateTime.now())
                        .reason(String.format("止盈：成本%.2f，当前%.2f，盈利%.1f%%",
                                avgCost, currentPrice, profitRate * 100))
                        .build();
            }
        }

        // ====== 卖出信号检测 ======
        if (hasPosition) {
            List<String> sellReasons = new ArrayList<>();
            TechnicalIndicator.MACDResult macd = TechnicalIndicator.macd(closes);
            if (macd.isDeathCross()) sellReasons.add("MACD死叉");
            if (analysis.getRsi6() > 75) sellReasons.add(String.format("RSI6超买=%.1f", analysis.getRsi6()));
            TechnicalIndicator.KDJResult kdj = TechnicalIndicator.kdj(bars);
            if (kdj.isDeathCross() && kdj.isOverbought()) sellReasons.add("KDJ死叉超买");
            if (currentPrice < analysis.getMa20() && analysis.getMa5() < analysis.getMa10())
                sellReasons.add("跌破MA20且短线走弱");

            if (sellReasons.size() >= minSellConditions) {
                int strength = Math.min(50 + sellReasons.size() * 10, 90);
                return TradeSignal.builder()
                        .signalId(UUID.randomUUID().toString())
                        .stockCode(stockCode).stockName(stockName)
                        .signalType(TradeSignal.SignalType.SELL).strength(strength)
                        .suggestedPrice(currentPrice).strategyName(getStrategyName())
                        .signalTime(LocalDateTime.now())
                        .reason("卖出信号：" + String.join("，", sellReasons))
                        .build();
            }
        }

        // ====== 买入信号检测 ======
        if (!hasPosition) {
            List<String> buyReasons = new ArrayList<>();
            TechnicalIndicator.MACDResult macd = TechnicalIndicator.macd(closes);
            if (macd.isGoldenCross()) buyReasons.add("MACD金叉");
            else if (macd.latestDif() > 0) buyReasons.add("MACD0轴上方");

            double rsi6 = analysis.getRsi6();
            if (rsi6 >= 30 && rsi6 <= 60)
                buyReasons.add(String.format("RSI健康=%.1f", rsi6));
            else if (rsi6 < 30)
                buyReasons.add(String.format("RSI超卖=%.1f", rsi6));

            TechnicalIndicator.KDJResult kdj = TechnicalIndicator.kdj(bars);
            if (kdj.isGoldenCross()) buyReasons.add("KDJ金叉");
            else if (kdj.isOversold()) buyReasons.add("KDJ超卖");

            if (currentPrice > analysis.getMa20() &&
                    TechnicalIndicator.isBullishAlignment(analysis.getMa5(), analysis.getMa10(),
                            analysis.getMa20(), analysis.getMa60()))
                buyReasons.add("均线多头排列");

            if (analysis.getVolumeRatio() > 1.2)
                buyReasons.add(String.format("量比=%.2f", analysis.getVolumeRatio()));

            // 综合评分 >= 60 且满足任一买入条件，或者综合评分 >= 65 直接买入
            boolean scoreSignal = analysis.getOverallScore() >= 65 ||
                    (analysis.getOverallScore() >= 60 && buyReasons.size() >= minBuyConditions);

            if (scoreSignal) {
                if (buyReasons.isEmpty()) buyReasons.add("综合评分" + analysis.getOverallScore());
                int strength = Math.min(50 + buyReasons.size() * 8 + (analysis.getOverallScore() - 60) / 2, 95);
                double posRatio = strength >= 80 ? maxPositionRatio :
                        (strength >= 70 ? maxPositionRatio * 0.7 : maxPositionRatio * 0.5);

                return TradeSignal.builder()
                        .signalId(UUID.randomUUID().toString())
                        .stockCode(stockCode).stockName(stockName)
                        .signalType(strength >= 80 ? TradeSignal.SignalType.STRONG_BUY : TradeSignal.SignalType.BUY)
                        .strength(strength)
                        .suggestedPrice(currentPrice)
                        .positionRatio(posRatio)
                        .stopLossPrice(currentPrice * (1 - stopLossPercent))
                        .takeProfitPrice(currentPrice * (1 + takeProfitPercent))
                        .strategyName(getStrategyName())
                        .signalTime(LocalDateTime.now())
                        .reason("买入信号：" + String.join("，", buyReasons))
                        .build();
            }
        }

        return buildHoldSignal(stockCode, stockName,
                String.format("综合评分%d，条件不足", analysis.getOverallScore()));
    }

    @Override
    public double calculatePositionSize(TradeSignal signal, Portfolio portfolio) {
        if (signal.getStrength() >= 80) return maxPositionRatio;
        if (signal.getStrength() >= 70) return maxPositionRatio * 0.7;
        return maxPositionRatio * 0.5;
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


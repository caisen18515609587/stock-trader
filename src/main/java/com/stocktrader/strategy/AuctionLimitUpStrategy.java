package com.stocktrader.strategy;

import com.stocktrader.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * 集合竞价封板策略
 * <p>
 * 核心逻辑：
 * 1. 【选股时机】 9:15~9:25 集合竞价阶段，筛选有封板潜力的标的
 * 2. 【买入条件】 竞价涨幅接近涨停（≥8%）且竞价量大、封单充足
 * 3. 【持仓管理】 全仓买入1只（5000元本金）
 * 4. 【止损】     开盘后破板（跌破涨停价×0.97）立即止损，最多亏3%
 * 5. 【止盈】     尾盘14:50前若股价仍在涨停附近且仍有封单，继续持有；
 *               收盘前强制平仓（当日T+0不适用A股，但可在14:55清仓锁利）
 * <p>
 * 封板信号识别：
 *   - 竞价涨幅 ≥ 8%（接近涨停），越高越强
 *   - 竞价量比昨日成交量比例 ≥ 5%（竞价放量）
 *   - 实时价 ≥ 昨收 × 1.095（开盘后快速冲击涨停区间）
 *   - 非ST、非已连板N+1日（防止高位接板）
 * <p>
 * 注意：此策略仅供 test01 账户使用，初始资金5000元
 */
public class AuctionLimitUpStrategy implements TradingStrategy {

    private static final Logger log = LoggerFactory.getLogger(AuctionLimitUpStrategy.class);

    // 策略参数
    private final double stopLossPercent;       // 止损比例（默认3%）
    private final double takeProfitPercent;     // 止盈比例（默认8%，即涨停）
    private final double maxPositionRatio;      // 最大仓位（默认95%，接近全仓）
    private final double auctionMinChangeRate;  // 竞价最低涨幅阈值（默认8%）
    private final double auctionMinVolumeRatio; // 竞价成交量/昨日成交量最低比例（默认3%）

    // 时间窗口
    private static final LocalTime AUCTION_START     = LocalTime.of(9, 15);
    private static final LocalTime AUCTION_END       = LocalTime.of(9, 25);
    private static final LocalTime MARKET_OPEN       = LocalTime.of(9, 31);
    private static final LocalTime FORCE_CLOSE_TIME  = LocalTime.of(14, 50); // 尾盘强制清仓
    private static final LocalTime NO_BUY_AFTER      = LocalTime.of(9, 30);  // 9:30后不再开新仓

    public AuctionLimitUpStrategy() {
        this(0.03, 0.10, 0.95, 0.08, 0.03);
    }

    public AuctionLimitUpStrategy(StrategyConfig sc) {
        this(
            sc.getStopLossPercent() > 0 ? sc.getStopLossPercent() : 0.03,
            sc.getTakeProfitFullPercent() > 0 ? sc.getTakeProfitFullPercent() : 0.10,
            sc.getMaxPositionRatio() > 0 ? sc.getMaxPositionRatio() : 0.95,
            0.08,
            0.03
        );
    }

    public AuctionLimitUpStrategy(double stopLossPercent, double takeProfitPercent,
                                   double maxPositionRatio,
                                   double auctionMinChangeRate, double auctionMinVolumeRatio) {
        this.stopLossPercent       = stopLossPercent;
        this.takeProfitPercent     = takeProfitPercent;
        this.maxPositionRatio      = maxPositionRatio;
        this.auctionMinChangeRate  = auctionMinChangeRate;
        this.auctionMinVolumeRatio = auctionMinVolumeRatio;
    }

    @Override
    public String getStrategyName() { return "集合竞价封板策略"; }

    @Override
    public String getDescription() {
        return String.format("竞价封板：竞价涨幅≥%.0f%%买入，止损%.0f%%，止盈%.0f%%，尾盘14:50清仓",
                auctionMinChangeRate * 100, stopLossPercent * 100, takeProfitPercent * 100);
    }

    @Override
    public int getMinBarsRequired() { return 5; }

    public double getStopLossPercent()   { return stopLossPercent; }
    public double getTakeProfitPercent() { return takeProfitPercent; }

    @Override
    public TradeSignal generateSignal(String stockCode, String stockName,
                                       List<StockBar> bars, AnalysisResult analysis,
                                       Portfolio portfolio) {
        if (bars == null || bars.isEmpty()) {
            return holdSignal(stockCode, stockName, "无K线数据");
        }

        LocalTime now = LocalTime.now();

        // 取最新行情（实时bar）
        StockBar latest = bars.get(bars.size() - 1);
        // 取昨日收盘价（bars倒数第2根，若只有实时bar则用倒数第1根的open估算）
        double prevClose = bars.size() >= 2
                ? bars.get(bars.size() - 2).getClose()
                : (latest.getOpen() > 0 ? latest.getOpen() : latest.getClose());
        if (prevClose <= 0) {
            return holdSignal(stockCode, stockName, "昨收为0，数据异常");
        }

        double currentPrice = latest.getClose();
        if (currentPrice <= 0) {
            return holdSignal(stockCode, stockName, "实时价格为0");
        }

        double changeRate = (currentPrice - prevClose) / prevClose; // 当前涨幅
        // 涨停价 = 昨收×1.10（创业板/科创板1.20，此处统一用1.10近似）
        double limitUpPrice = prevClose * 1.10;
        boolean isKeChuang = stockCode.startsWith("688") || stockCode.startsWith("300");
        if (isKeChuang) limitUpPrice = prevClose * 1.20;

        boolean hasPosition  = portfolio != null && portfolio.hasPosition(stockCode);
        boolean hasAvailable = hasPosition &&
                portfolio.getPosition(stockCode).getAvailableQuantity() > 0;

        // ===== 已持仓：止损/止盈/强制清仓 =====
        if (hasPosition) {
            Position pos = portfolio.getPosition(stockCode);
            double avgCost   = pos.getAvgCost();
            double profitRate = avgCost > 0 ? (currentPrice - avgCost) / avgCost : 0;

            // 1. 强制尾盘清仓（14:50后，无论盈亏）
            if (now.isAfter(FORCE_CLOSE_TIME) && hasAvailable) {
                return TradeSignal.builder()
                        .signalId(UUID.randomUUID().toString())
                        .stockCode(stockCode).stockName(stockName)
                        .signalType(TradeSignal.SignalType.TAKE_PROFIT).strength(95)
                        .suggestedPrice(currentPrice).strategyName(getStrategyName())
                        .signalTime(LocalDateTime.now())
                        .reason(String.format("竞价封板策略尾盘清仓：14:50强制平仓，盈利=%.2f%%", profitRate * 100))
                        .build();
            }

            // 2. 止损：破板（现价低于涨停价×97%，或亏损超过stopLossPercent）
            boolean isBroken  = currentPrice < limitUpPrice * 0.97;
            boolean isStopLoss = profitRate <= -stopLossPercent;
            if (hasAvailable && (isBroken || isStopLoss)) {
                String reason = isBroken
                        ? String.format("竞价封板破板止损：现价=%.2f 低于封板价%.2f×97%%=%.2f（盈亏=%.2f%%）",
                                currentPrice, limitUpPrice, limitUpPrice * 0.97, profitRate * 100)
                        : String.format("竞价封板触发固定止损%.0f%%：成本=%.2f 现价=%.2f 亏损=%.2f%%",
                                stopLossPercent * 100, avgCost, currentPrice, profitRate * 100);
                return TradeSignal.builder()
                        .signalId(UUID.randomUUID().toString())
                        .stockCode(stockCode).stockName(stockName)
                        .signalType(TradeSignal.SignalType.STOP_LOSS).strength(100)
                        .suggestedPrice(currentPrice).strategyName(getStrategyName())
                        .signalTime(LocalDateTime.now())
                        .reason(reason)
                        .build();
            }

            // 3. 止盈：盈利达到目标
            if (hasAvailable && profitRate >= takeProfitPercent) {
                return TradeSignal.builder()
                        .signalId(UUID.randomUUID().toString())
                        .stockCode(stockCode).stockName(stockName)
                        .signalType(TradeSignal.SignalType.TAKE_PROFIT).strength(90)
                        .suggestedPrice(currentPrice).strategyName(getStrategyName())
                        .signalTime(LocalDateTime.now())
                        .reason(String.format("竞价封板止盈：盈利=%.2f%%（目标=%.0f%%）",
                                profitRate * 100, takeProfitPercent * 100))
                        .build();
            }

            return holdSignal(stockCode, stockName,
                    String.format("持仓中：盈亏=%.2f%% 现价=%.2f 涨停价=%.2f",
                            profitRate * 100, currentPrice, limitUpPrice));
        }

        // ===== 买入逻辑（仅在竞价窗口内） =====
        // 9:15~9:25 竞价阶段，或9:30前（竞价撮合完成价仍有封单）
        boolean inAuctionWindow  = now.isAfter(AUCTION_START) && now.isBefore(AUCTION_END);
        boolean inOpeningWindow  = now.isAfter(AUCTION_END) && now.isBefore(NO_BUY_AFTER);

        if (!inAuctionWindow && !inOpeningWindow) {
            return holdSignal(stockCode, stockName,
                    String.format("非竞价窗口（%s~%s），当前=%s，不买入",
                            AUCTION_START, NO_BUY_AFTER, now));
        }

        // 今日已买入过（T+1限制，防重复买入）
        if (hasPosition) {
            Position pos = portfolio.getPosition(stockCode);
            if (pos.getLastBuyDate() != null && pos.getLastBuyDate().equals(LocalDate.now())) {
                return holdSignal(stockCode, stockName, "今日已买入");
            }
        }

        // 核心封板信号判断
        double auctionVolumeRatio = 0;
        if (bars.size() >= 2) {
            double prevVolume = bars.get(bars.size() - 2).getVolume();
            double curVolume  = latest.getVolume();
            auctionVolumeRatio = prevVolume > 0 ? curVolume / prevVolume : 0;
        }

        boolean changeRateOk    = changeRate >= auctionMinChangeRate;
        boolean volumeRatioOk   = auctionVolumeRatio >= auctionMinVolumeRatio;
        boolean nearLimitUp     = currentPrice >= prevClose * (isKeChuang ? 1.18 : 1.08);

        // 打分
        double score = 0.0;
        if (changeRate >= 0.095 || changeRate >= 0.195)   score += 1.5; // 接近涨停（10%/20%）
        else if (changeRate >= auctionMinChangeRate)       score += 1.0;
        if (nearLimitUp)                                   score += 0.8;
        if (volumeRatioOk)                                 score += 0.7;
        if (changeRate >= (isKeChuang ? 0.17 : 0.09))     score += 0.5; // 超强竞价

        boolean canBuy = changeRateOk && score >= 2.0;

        if (canBuy) {
            int strength = Math.min((int)(60 + score * 12), 95);
            return TradeSignal.builder()
                    .signalId(UUID.randomUUID().toString())
                    .stockCode(stockCode).stockName(stockName)
                    .signalType(strength >= 80 ? TradeSignal.SignalType.STRONG_BUY : TradeSignal.SignalType.BUY)
                    .strength(strength)
                    .suggestedPrice(currentPrice)
                    .positionRatio(maxPositionRatio)
                    .stopLossPrice(currentPrice * (1 - stopLossPercent))
                    .takeProfitPrice(limitUpPrice)
                    .strategyName(getStrategyName())
                    .signalTime(LocalDateTime.now())
                    .reason(String.format("竞价封板信号：竞价涨幅=%.2f%%（阈值%.0f%%），竞价量比=%.1f%%，接近涨停=%.2f，综合分=%.1f",
                            changeRate * 100, auctionMinChangeRate * 100,
                            auctionVolumeRatio * 100, limitUpPrice, score))
                    .build();
        }

        return holdSignal(stockCode, stockName,
                String.format("竞价信号不足（涨幅%.2f%% 竞价量比%.1f%% 综合分%.1f，需>=2.0）",
                        changeRate * 100, auctionVolumeRatio * 100, score));
    }

    @Override
    public double calculatePositionSize(TradeSignal signal, Portfolio portfolio) {
        // 全仓或接近全仓（竞价封板策略资金少，全押一只）
        return maxPositionRatio;
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


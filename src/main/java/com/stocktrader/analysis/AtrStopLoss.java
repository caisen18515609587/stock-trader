package com.stocktrader.analysis;

import com.stocktrader.model.StockBar;

import java.util.List;

/**
 * ATR 动态止损工具
 * <p>
 * 动态止损原理：
 *   止损价 = 入场价 - N × ATR(period)
 * <p>
 * 与固定比例止损的对比：
 *   - 固定比例（如 -5%）：对低波动率股票太宽，对高波动率股票太窄
 *   - ATR动态止损：根据近期真实波幅自动调整，低波动=止损紧，高波动=止损宽
 * <p>
 * Chandelier Exit（吊灯止损）：
 *   止损价 = 持仓期间最高价 - N × ATR
 *   → 随股价上涨而上移，形成跟踪止损
 * <p>
 * 使用方式：
 * <pre>
 *   // 在买入时计算初始止损价（2倍ATR）
 *   double stopPrice = AtrStopLoss.calcStopPrice(bars, entryPrice, 14, 2.0);
 *
 *   // 持仓过程中用吊灯止损（跟踪上涨）
 *   double trailingStop = AtrStopLoss.chandelierStop(bars, 14, 3.0);
 *
 *   // 判断是否触发止损
 *   if (currentPrice <= stopPrice) { // 止损出局 }
 * </pre>
 */
public class AtrStopLoss {

    private AtrStopLoss() {}

    /**
     * 计算基于ATR的初始止损价
     * @param bars       K线列表（时间升序）
     * @param entryPrice 入场价格
     * @param atrPeriod  ATR周期（常用14）
     * @param multiplier ATR倍数（常用1.5~3.0）
     * @return 止损价格（入场价 - multiplier × ATR）
     */
    public static double calcStopPrice(List<StockBar> bars, double entryPrice,
                                        int atrPeriod, double multiplier) {
        double atr = latestAtr(bars, atrPeriod);
        if (Double.isNaN(atr) || atr <= 0) {
            // 降级为固定5%止损
            return entryPrice * 0.95;
        }
        return entryPrice - multiplier * atr;
    }

    /**
     * 吊灯止损（Chandelier Exit）：持仓最高价 - N×ATR
     * <p>
     * 该止损价会随股价上涨自动上移，锁定利润。
     * @param bars       K线列表（从买入日到今日）
     * @param atrPeriod  ATR周期
     * @param multiplier ATR倍数（常用2.0~3.0）
     * @return 当前跟踪止损价
     */
    public static double chandelierStop(List<StockBar> bars, int atrPeriod, double multiplier) {
        if (bars == null || bars.isEmpty()) return 0;
        double highestHigh = bars.stream()
                .mapToDouble(StockBar::getHigh)
                .max().orElse(0);
        double atr = latestAtr(bars, atrPeriod);
        if (Double.isNaN(atr) || atr <= 0) return highestHigh * 0.95;
        return highestHigh - multiplier * atr;
    }

    /**
     * 计算止损价格对应的止损比例（相对于当前价）
     * @param currentPrice 当前价格
     * @param stopPrice    止损价格
     * @return 止损幅度（如0.07 = 7%）
     */
    public static double stopLossPercent(double currentPrice, double stopPrice) {
        if (currentPrice <= 0) return 0.05;
        return Math.max(0, (currentPrice - stopPrice) / currentPrice);
    }

    /**
     * 获取最新ATR值
     * @param bars   K线数据
     * @param period ATR周期
     */
    public static double latestAtr(List<StockBar> bars, int period) {
        if (bars == null || bars.size() < period) return Double.NaN;
        double[] atrArr = TechnicalIndicator.atrArray(bars, period);
        // 取最后一个有效值
        for (int i = atrArr.length - 1; i >= 0; i--) {
            if (!Double.isNaN(atrArr[i]) && atrArr[i] > 0) return atrArr[i];
        }
        return Double.NaN;
    }

    /**
     * 计算ATR占当前价格的百分比（波动率指标）
     * @param bars   K线数据
     * @param period ATR周期
     * @return ATR / 最新收盘价（如0.03 = 3%日波动率）
     */
    public static double atrPercent(List<StockBar> bars, int period) {
        if (bars == null || bars.isEmpty()) return 0;
        double atr = latestAtr(bars, period);
        double lastClose = bars.get(bars.size() - 1).getClose();
        return (Double.isNaN(atr) || lastClose <= 0) ? 0 : atr / lastClose;
    }

    /**
     * 根据ATR动态调整仓位大小（风险等价分配）
     * <p>
     * 目标风险 = 总资金 × riskPercent
     * 每股风险 = multiplier × ATR
     * 仓位数量 = 目标风险 / 每股风险
     * @param totalCapital 总资金
     * @param riskPercent  愿意承担的单笔最大亏损（如0.01 = 1%资金）
     * @param bars         K线数据
     * @param atrPeriod    ATR周期
     * @param multiplier   ATR倍数
     * @param price        当前价格（每股价格）
     * @return 建议买入股数（已向下取整到100的倍数）
     */
    public static int calcPositionByRisk(double totalCapital, double riskPercent,
                                          List<StockBar> bars, int atrPeriod,
                                          double multiplier, double price) {
        double atr = latestAtr(bars, atrPeriod);
        if (Double.isNaN(atr) || atr <= 0 || price <= 0) return 0;
        double riskPerShare = multiplier * atr;
        double targetRisk   = totalCapital * riskPercent;
        int quantity        = (int) (targetRisk / riskPerShare / 100) * 100;
        // 不超过资金限制
        double maxQty       = (int) (totalCapital / price / 100) * 100;
        return (int) Math.min(quantity, maxQty);
    }

    /**
     * 计算基于ATR波动率的动态止盈目标（减仓线）
     * <p>
     * 核心思路：不同股票的日内波动幅度（ATR%）差异很大。
     * 固定止盈比例对低波动股票太宽（迟迟达不到），对高波动股票太窄（频繁被洗出）。
     * <p>
     * 计算方式：
     *   动态目标 = baseTakeProfitRate × (avgAtrPct / targetAtrPct)
     *   其中 targetAtrPct 是"标准"日波动率（默认1.5%，代表中等波动的股票）
     * <p>
     * 举例：
     *   - 基础止盈 8%，标准ATR% 1.5%
     *   - 某股 ATR%=3%（高波动）→ 动态目标 = 8% × (3/1.5) = 16%（放宽，顺势拿利润）
     *   - 某股 ATR%=0.8%（低波动）→ 动态目标 = 8% × (0.8/1.5) = 4.3%（收紧，快速兑现）
     * <p>
     * 上下限保护：最小不低于 baseTakeProfitRate × 0.5，最大不超过 baseTakeProfitRate × 1.5
     *
     * @param bars              K线数据
     * @param atrPeriod         ATR周期（通常14）
     * @param baseTakeProfitRate 配置的基础止盈比例（如0.08）
     * @param targetAtrPct      基准波动率（默认0.015 = 1.5%）
     * @return 动态调整后的止盈比例
     */
    public static double dynamicTakeProfitRate(List<StockBar> bars, int atrPeriod,
                                                double baseTakeProfitRate, double targetAtrPct) {
        double atrPct = atrPercent(bars, atrPeriod);
        if (atrPct <= 0 || Double.isNaN(atrPct)) return baseTakeProfitRate;

        // 波动率比值：ATR% / 目标ATR%
        double ratio = atrPct / targetAtrPct;

        // 动态目标 = 基础目标 × 比值，再加上限保护
        // 上限从 2.5 收窄到 1.5：防止高波动股（如小盘题材股 ATR% 可达 3~4%）
        // 将止盈目标抬高到 2x~3x 基础值，导致止盈永远无法触发，反而拿着利润等待回调
        double dynamicRate = baseTakeProfitRate * ratio;
        double minRate = baseTakeProfitRate * 0.5;   // 最低不低于基础的50%
        double maxRate = baseTakeProfitRate * 1.5;   // 最高不超过基础的150%（原250%过高）
        return Math.max(minRate, Math.min(maxRate, dynamicRate));
    }

    /**
     * 使用默认基准波动率（1.5%）计算动态止盈目标
     */
    public static double dynamicTakeProfitRate(List<StockBar> bars, int atrPeriod,
                                                double baseTakeProfitRate) {
        return dynamicTakeProfitRate(bars, atrPeriod, baseTakeProfitRate, 0.015);
    }

    /**
     * 基于ATR计算动态止盈价格（绝对价格）
     * <p>
     * 止盈价 = 入场价 × (1 + 动态止盈比例)
     *
     * @param bars              K线数据
     * @param entryPrice        入场均价
     * @param atrPeriod         ATR周期
     * @param baseTakeProfitRate 配置的基础止盈比例
     * @return 动态止盈目标价格
     */
    public static double dynamicTakeProfitPrice(List<StockBar> bars, double entryPrice,
                                                 int atrPeriod, double baseTakeProfitRate) {
        double rate = dynamicTakeProfitRate(bars, atrPeriod, baseTakeProfitRate);
        return entryPrice * (1 + rate);
    }

    /**
     * 计算动态止盈摘要描述（用于日志）
     *
     * @param bars              K线数据
     * @param atrPeriod         ATR周期
     * @param baseTakeProfitRate 配置的基础止盈比例
     * @return 摘要字符串，如 "ATR动态止盈: ATR=2.1% 动态目标=11.2%（基础8%×1.4倍）"
     */
    public static String dynamicTakeProfitDesc(List<StockBar> bars, int atrPeriod,
                                                double baseTakeProfitRate) {
        double atrPct = atrPercent(bars, atrPeriod) * 100;
        double dynRate = dynamicTakeProfitRate(bars, atrPeriod, baseTakeProfitRate) * 100;
        double ratio = baseTakeProfitRate > 0 ? dynRate / (baseTakeProfitRate * 100) : 1.0;
        return String.format("ATR动态止盈: ATR=%.1f%% 动态目标=%.1f%%（基础%.0f%%×%.1f倍）",
                atrPct, dynRate, baseTakeProfitRate * 100, ratio);
    }
}


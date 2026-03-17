package com.stocktrader.trading;

import com.stocktrader.analysis.StockAnalyzer;
import com.stocktrader.model.*;
import com.stocktrader.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 历史回测引擎（含滑点模型）
 * <p>
 * 采用逐Bar回测方式，按时间顺序逐根K线执行策略。
 * <p>
 * 滑点模型（三种可选）：
 *   FIXED_RATIO  - 固定比例滑点（默认），买入价上浮 N%，卖出价下浮 N%
 *   FIXED_AMOUNT - 固定金额滑点（每股 N 元）
 *   VOLUME_IMPACT - 成交量冲击模型：滑点 = base + 成交量占日均成交量比 * impact 系数
 */
public class BacktestEngine {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);

    private final StockAnalyzer stockAnalyzer;
    private final FeeCalculator feeCalculator;

    public BacktestEngine() {
        this.stockAnalyzer = new StockAnalyzer();
        this.feeCalculator = new FeeCalculator();
    }

    // ============================= 滑点模型 =============================

    /** 滑点类型 */
    public enum SlippageModel {
        /** 固定比例（如 0.001 = 0.1%）*/
        FIXED_RATIO,
        /** 固定金额（每股 N 元）*/
        FIXED_AMOUNT,
        /** 成交量冲击：滑点随买入量占日均成交量的比例增大 */
        VOLUME_IMPACT
    }

    /** 滑点配置 */
    public static class SlippageConfig {
        public final SlippageModel model;
        /** FIXED_RATIO: 比例值（0.001 = 0.1%）；FIXED_AMOUNT: 每股元数；VOLUME_IMPACT: 基础比例 */
        public final double value;
        /** 仅 VOLUME_IMPACT 使用：冲击系数（每占1%日均量，额外滑点 impactCoef%） */
        public final double impactCoef;

        private SlippageConfig(SlippageModel model, double value, double impactCoef) {
            this.model = model;
            this.value = value;
            this.impactCoef = impactCoef;
        }

        /** 固定比例滑点（推荐默认，0.1%） */
        public static SlippageConfig fixedRatio(double ratio) {
            return new SlippageConfig(SlippageModel.FIXED_RATIO, ratio, 0);
        }

        /** 固定金额滑点 */
        public static SlippageConfig fixedAmount(double amountPerShare) {
            return new SlippageConfig(SlippageModel.FIXED_AMOUNT, amountPerShare, 0);
        }

        /** 成交量冲击模型（base=基础滑点比例，impactCoef=冲击系数） */
        public static SlippageConfig volumeImpact(double base, double impactCoef) {
            return new SlippageConfig(SlippageModel.VOLUME_IMPACT, base, impactCoef);
        }

        /** 默认：固定0.1%滑点 */
        public static SlippageConfig defaultConfig() {
            return fixedRatio(0.001);
        }

        /**
         * 计算实际成交价
         * @param signalPrice  信号价（通常为当日收盘价）
         * @param isBuy        true=买入（价格上浮），false=卖出（价格下浮）
         * @param quantity     委托数量（股）
         * @param avgDailyVol  近N日均量（成交量冲击模型用，其他模型传0）
         * @return 实际成交价（含滑点）
         */
        public double apply(double signalPrice, boolean isBuy, int quantity, long avgDailyVol) {
            double slipRatio;
            switch (model) {
                case FIXED_RATIO:
                    slipRatio = value;
                    break;
                case FIXED_AMOUNT:
                    slipRatio = signalPrice > 0 ? value / signalPrice : 0;
                    break;
                case VOLUME_IMPACT:
                    if (avgDailyVol > 0) {
                        double volRatio = (double) quantity / avgDailyVol;
                        slipRatio = value + volRatio * impactCoef;
                    } else {
                        slipRatio = value;
                    }
                    break;
                default:
                    slipRatio = 0;
            }
            // 买入：价格上浮（以略高价成交）；卖出：价格下浮
            return isBuy ? signalPrice * (1 + slipRatio) : signalPrice * (1 - slipRatio);
        }

        @Override
        public String toString() {
            switch (model) {
                case FIXED_RATIO:   return String.format("固定比例滑点=%.3f%%", value * 100);
                case FIXED_AMOUNT:  return String.format("固定金额滑点=%.4f元/股", value);
                case VOLUME_IMPACT: return String.format("成交量冲击(基础=%.3f%% 冲击系数=%.4f)", value * 100, impactCoef);
                default:            return "无滑点";
            }
        }
    }

    // ============================= 回测配置 =============================

    public static class BacktestConfig {
        public final String stockCode;
        public final String stockName;
        public final double initialCapital;
        public final LocalDate startDate;
        public final LocalDate endDate;
        public final double maxPositionRatio;
        public final int warmupBars;
        /** 滑点配置（默认固定0.1%） */
        public final SlippageConfig slippage;

        public BacktestConfig(String stockCode, String stockName, double initialCapital,
                              LocalDate startDate, LocalDate endDate) {
            this(stockCode, stockName, initialCapital, startDate, endDate,
                    0.5, 60, SlippageConfig.defaultConfig());
        }

        public BacktestConfig(String stockCode, String stockName, double initialCapital,
                              LocalDate startDate, LocalDate endDate,
                              double maxPositionRatio, int warmupBars) {
            this(stockCode, stockName, initialCapital, startDate, endDate,
                    maxPositionRatio, warmupBars, SlippageConfig.defaultConfig());
        }

        public BacktestConfig(String stockCode, String stockName, double initialCapital,
                              LocalDate startDate, LocalDate endDate,
                              double maxPositionRatio, int warmupBars, SlippageConfig slippage) {
            this.stockCode = stockCode;
            this.stockName = stockName;
            this.initialCapital = initialCapital;
            this.startDate = startDate;
            this.endDate = endDate;
            this.maxPositionRatio = maxPositionRatio;
            this.warmupBars = warmupBars;
            this.slippage = slippage != null ? slippage : SlippageConfig.defaultConfig();
        }
    }

    // ============================= 回测结果 =============================

    public static class BacktestResult {
        public final String stockCode;
        public final String stockName;
        public final String strategyName;
        public final LocalDate startDate;
        public final LocalDate endDate;

        public final double initialCapital;
        public final double finalCapital;
        public final double totalReturn;
        public final double annualReturn;
        public final double maxDrawdown;
        public final double sharpeRatio;     // 基于日收益率计算的标准夏普比率
        public final double calmarRatio;     // Calmar = 年化收益 / 最大回撤
        public final double winRate;
        public final double profitLossRatio;

        public final int totalTrades;
        public final int winTrades;
        public final int lossTrades;
        public final double totalFee;
        public final double totalSlippage;   // 新增：总滑点成本

        public final double benchmarkReturn;
        public final String slippageDesc;    // 新增：滑点配置描述

        public final List<Order> tradeHistory;
        public final List<double[]> equityCurve;  // [bar索引, 总资产]

        public BacktestResult(String stockCode, String stockName, String strategyName,
                              LocalDate startDate, LocalDate endDate, double initialCapital,
                              double finalCapital, double maxDrawdown, double winRate,
                              double profitLossRatio, int totalTrades, int winTrades, int lossTrades,
                              double totalFee, double totalSlippage, double benchmarkReturn,
                              String slippageDesc, List<double[]> dailyReturns,
                              List<Order> tradeHistory, List<double[]> equityCurve) {
            this.stockCode = stockCode;
            this.stockName = stockName;
            this.strategyName = strategyName;
            this.startDate = startDate;
            this.endDate = endDate;
            this.initialCapital = initialCapital;
            this.finalCapital = finalCapital;
            this.totalReturn = (finalCapital - initialCapital) / initialCapital * 100;
            this.maxDrawdown = maxDrawdown;
            this.winRate = winRate;
            this.profitLossRatio = profitLossRatio;
            this.totalTrades = totalTrades;
            this.winTrades = winTrades;
            this.lossTrades = lossTrades;
            this.totalFee = totalFee;
            this.totalSlippage = totalSlippage;
            this.benchmarkReturn = benchmarkReturn;
            this.slippageDesc = slippageDesc;
            this.tradeHistory = tradeHistory;
            this.equityCurve = equityCurve;

            // 年化收益率（交易日252天）
            long days = startDate.until(endDate).getDays();
            this.annualReturn = days > 0 ? totalReturn * 365.0 / days : totalReturn;

            // 基于日收益率序列计算真正的夏普比率（年化无风险利率 3%）
            this.sharpeRatio = calcSharpe(dailyReturns, 0.03);

            // Calmar 比率
            this.calmarRatio = maxDrawdown > 0 ? annualReturn / maxDrawdown : 0;
        }

        /** 基于日收益率序列计算夏普比率 */
        private static double calcSharpe(List<double[]> dailyReturns, double riskFreeAnnual) {
            if (dailyReturns == null || dailyReturns.size() < 2) return 0;
            double riskFreeDaily = riskFreeAnnual / 252.0;
            double[] rets = dailyReturns.stream().mapToDouble(r -> r[0]).toArray();
            double mean = 0;
            for (double r : rets) mean += r;
            mean /= rets.length;
            double variance = 0;
            for (double r : rets) variance += (r - mean) * (r - mean);
            variance /= (rets.length - 1);
            double std = Math.sqrt(variance);
            if (std == 0) return 0;
            // 年化夏普 = (日均超额收益 / 日收益率标准差) * sqrt(252)
            return (mean - riskFreeDaily) / std * Math.sqrt(252);
        }

        public void print() {
            String sep  = "================================================================";
            String sepm = "----------------------------------------------------------------";
            System.out.println("\n" + sep);
            System.out.printf("回测报告：%s %s%n", stockCode, stockName);
            System.out.printf("策略：%s%n", strategyName);
            System.out.printf("时间：%s ~ %s%n", startDate, endDate);
            System.out.printf("滑点：%s%n", slippageDesc);
            System.out.println(sepm);
            System.out.printf("初始资金：     %,.2f 元%n", initialCapital);
            System.out.printf("最终资金：     %,.2f 元%n", finalCapital);
            System.out.printf("总收益率：     %+.2f%%%n", totalReturn);
            System.out.printf("年化收益率：   %+.2f%%%n", annualReturn);
            System.out.printf("基准收益(持有)：%+.2f%%%n", benchmarkReturn);
            System.out.printf("超额收益：     %+.2f%%%n", totalReturn - benchmarkReturn);
            System.out.println(sepm);
            System.out.printf("最大回撤：     %.2f%%%n", maxDrawdown);
            System.out.printf("夏普比率：     %.3f%n", sharpeRatio);
            System.out.printf("Calmar比率：   %.3f%n", calmarRatio);
            System.out.printf("胜率：         %.1f%% (%d/%d)%n", winRate, winTrades, totalTrades);
            System.out.printf("盈亏比：       %.2f%n", profitLossRatio);
            System.out.printf("总交易次数：   %d 次%n", totalTrades);
            System.out.printf("总手续费：     %,.2f 元%n", totalFee);
            System.out.printf("总滑点成本：   %,.2f 元%n", totalSlippage);
            System.out.printf("总摩擦成本：   %,.2f 元（费+滑）%n", totalFee + totalSlippage);
            System.out.println(sep);
        }
    }

    // ============================= 核心回测逻辑 =============================

    /**
     * 执行历史回测
     * @param config   回测配置（含滑点设置）
     * @param allBars  历史K线（包含预热数据）
     * @param strategy 交易策略
     */
    public BacktestResult run(BacktestConfig config, List<StockBar> allBars, TradingStrategy strategy) {
        log.info("开始回测：{} {} {} ~ {}  策略：{}  滑点：{}",
                config.stockCode, config.stockName, config.startDate, config.endDate,
                strategy.getStrategyName(), config.slippage);

        // 过滤回测区间
        List<StockBar> backtestBars = new ArrayList<>();
        for (StockBar bar : allBars) {
            LocalDate barDate = bar.getDate();
            if (barDate != null && !barDate.isBefore(config.startDate) && !barDate.isAfter(config.endDate)) {
                backtestBars.add(bar);
            }
        }
        if (backtestBars.isEmpty()) {
            log.warn("回测区间内没有数据");
            return null;
        }

        // 初始化模拟账户
        Portfolio portfolio = new Portfolio(
                "backtest_" + config.stockCode,
                "回测账户-" + config.stockCode,
                config.initialCapital,
                Portfolio.AccountMode.PAPER
        );

        List<Order> tradeHistory  = new ArrayList<>();
        List<double[]> equityCurve   = new ArrayList<>();
        List<double[]> dailyReturns  = new ArrayList<>();  // 用于夏普计算
        double maxAssets   = config.initialCapital;
        double maxDrawdown = 0;
        double totalFee    = 0;
        double totalSlippage = 0;
        List<Double> tradePnls = new ArrayList<>();
        double prevAssets = config.initialCapital;

        // 基准：首日买入持有
        double firstClose = backtestBars.get(0).getClose();
        double lastClose  = backtestBars.get(backtestBars.size() - 1).getClose();
        double benchmarkReturn = firstClose > 0 ? (lastClose - firstClose) / firstClose * 100 : 0;

        // 计算近20日均量（成交量冲击模型用）
        long avgDailyVol = calcAvgVolume(backtestBars, 20);

        // 逐Bar回测
        for (int i = config.warmupBars; i < backtestBars.size(); i++) {
            StockBar currentBar = backtestBars.get(i);
            double signalPrice = currentBar.getClose(); // 信号价：收盘价

            int allBarsEndIdx = findBarIndex(allBars, currentBar);
            List<StockBar> historicalBars = allBarsEndIdx >= 0
                    ? allBars.subList(0, allBarsEndIdx + 1)
                    : backtestBars.subList(0, i + 1);

            if (historicalBars.size() < strategy.getMinBarsRequired()) continue;

            // 更新持仓现价（用信号价，不含滑点，仅用于计算账户净值）
            portfolio.updatePositionPrice(config.stockCode, signalPrice);

            // 生成策略信号
            AnalysisResult analysis = stockAnalyzer.analyze(
                    config.stockCode, config.stockName, historicalBars);
            TradeSignal signal = strategy.generateSignal(
                    config.stockCode, config.stockName, historicalBars, analysis, portfolio);

            // ---- 买入处理 ----
            if (signal.getSignalType().isBuySignal() && !portfolio.hasPosition(config.stockCode)) {
                double posRatio  = strategy.calculatePositionSize(signal, portfolio);
                double buyAmount = portfolio.getAvailableCash() * posRatio;
                int quantity = (int) (buyAmount / signalPrice / 100) * 100;
                if (quantity == 0 && portfolio.getAvailableCash() >= signalPrice * 100) {
                    quantity = (int) (portfolio.getAvailableCash() / signalPrice / 100) * 100;
                }

                if (quantity > 0) {
                    // === 滑点：买入价上浮 ===
                    double execPrice = config.slippage.apply(signalPrice, true, quantity, avgDailyVol);
                    double slippageCost = (execPrice - signalPrice) * quantity;
                    totalSlippage += slippageCost;

                    double actualAmount = quantity * execPrice;
                    String exchange = currentBar.getStockCode().startsWith("6") ? "SH" : "SZ";
                    FeeCalculator.FeeDetail fee = feeCalculator.calculateBuyFee(actualAmount, exchange);
                    totalFee += fee.total;

                    Order order = buildOrder(config, Order.OrderType.BUY, execPrice,
                            quantity, actualAmount, fee, currentBar.getDateTime(),
                            signal.getReason() + String.format("（含滑点%.4f元）", slippageCost));
                    portfolio.executeBuy(order);
                    tradeHistory.add(order);
                    log.debug("[{}] 买入 {}股 信号价@{} 实际成交@{} 滑点={}",
                            currentBar.getDate(), quantity,
                            String.format("%.3f", signalPrice),
                            String.format("%.3f", execPrice),
                            String.format("%.4f", slippageCost));
                }

            // ---- 卖出处理 ----
            } else if (signal.getSignalType().isSellSignal() && portfolio.hasPosition(config.stockCode)) {
                Position pos = portfolio.getPosition(config.stockCode);
                int quantity = pos.getAvailableQuantity();
                if (quantity <= 0) {
                    // 回测中忽略T+1限制，直接用全部持仓
                    quantity = pos.getQuantity();
                }
                if (quantity <= 0) continue;

                if (signal.getSignalType() == TradeSignal.SignalType.TAKE_PROFIT) {
                    quantity = Math.max((quantity / 2 / 100) * 100, 100);
                }

                // === 滑点：卖出价下浮 ===
                double execPrice = config.slippage.apply(signalPrice, false, quantity, avgDailyVol);
                double slippageCost = (signalPrice - execPrice) * quantity;
                totalSlippage += slippageCost;

                double actualAmount = quantity * execPrice;
                String exchange = currentBar.getStockCode().startsWith("6") ? "SH" : "SZ";
                FeeCalculator.FeeDetail fee = feeCalculator.calculateSellFee(actualAmount, exchange);
                totalFee += fee.total;

                double pnl = (execPrice - pos.getAvgCost()) * quantity - fee.total - slippageCost;
                tradePnls.add(pnl);

                Order order = buildOrder(config, Order.OrderType.SELL, execPrice,
                        quantity, actualAmount, fee, currentBar.getDateTime(),
                        signal.getReason() + String.format("（含滑点%.4f元）", slippageCost));
                portfolio.executeSell(order);
                tradeHistory.add(order);
                log.debug("[{}] 卖出 {}股 信号价@{} 实际成交@{} PnL={}",
                        currentBar.getDate(), quantity,
                        String.format("%.3f", signalPrice),
                        String.format("%.3f", execPrice),
                        String.format("%.2f", pnl));
            }

            // 记录资产曲线
            double totalAssets = portfolio.getTotalAssets();
            equityCurve.add(new double[]{i, totalAssets});

            // 日收益率（用于夏普）
            if (prevAssets > 0) {
                dailyReturns.add(new double[]{(totalAssets - prevAssets) / prevAssets});
            }
            prevAssets = totalAssets;

            // 最大回撤
            maxAssets = Math.max(maxAssets, totalAssets);
            double drawdown = maxAssets > 0 ? (maxAssets - totalAssets) / maxAssets * 100 : 0;
            maxDrawdown = Math.max(maxDrawdown, drawdown);
        }

        // 回测结束：处理未平仓
        if (portfolio.hasPosition(config.stockCode)) {
            double lp = backtestBars.get(backtestBars.size() - 1).getClose();
            portfolio.updatePositionPrice(config.stockCode, lp);
            Position finalPos = portfolio.getPosition(config.stockCode);
            if (finalPos != null && finalPos.getQuantity() > 0) {
                double unrealizedPnl = (lp - finalPos.getAvgCost()) * finalPos.getQuantity();
                tradePnls.add(unrealizedPnl);
            }
        }

        // 统计
        int winTrades   = (int) tradePnls.stream().filter(p -> p > 0).count();
        int totalTrades = tradePnls.size();
        double winRate  = totalTrades > 0 ? (double) winTrades / totalTrades * 100 : 0;
        double avgWin   = tradePnls.stream().filter(p -> p > 0).mapToDouble(Double::doubleValue).average().orElse(0);
        double avgLoss  = Math.abs(tradePnls.stream().filter(p -> p < 0).mapToDouble(Double::doubleValue).average().orElse(-1));
        double plRatio  = avgLoss > 0 ? avgWin / avgLoss : avgWin;

        BacktestResult result = new BacktestResult(
                config.stockCode, config.stockName, strategy.getStrategyName(),
                config.startDate, config.endDate, config.initialCapital,
                portfolio.getTotalAssets(), maxDrawdown, winRate, plRatio,
                totalTrades, winTrades, totalTrades - winTrades,
                totalFee, totalSlippage, benchmarkReturn,
                config.slippage.toString(), dailyReturns, tradeHistory, equityCurve
        );

        log.info("回测完成：{}  收益率{}%  基准{}%  夏普{}  最大回撤{}%  总滑点{}元",
                strategy.getStrategyName(),
                String.format("%.2f", result.totalReturn),
                String.format("%.2f", benchmarkReturn),
                String.format("%.3f", result.sharpeRatio),
                String.format("%.2f", maxDrawdown),
                String.format("%.2f", totalSlippage));

        return result;
    }

    // ============================= 辅助方法 =============================

    /** 计算近 n 日均量 */
    private long calcAvgVolume(List<StockBar> bars, int n) {
        int size = bars.size();
        if (size == 0) return 0;
        int from = Math.max(0, size - n);
        long sum = 0;
        for (int i = from; i < size; i++) sum += bars.get(i).getVolume();
        return (size - from) > 0 ? sum / (size - from) : 0;
    }

    private int findBarIndex(List<StockBar> allBars, StockBar target) {
        for (int i = allBars.size() - 1; i >= 0; i--) {
            if (allBars.get(i).getDateTime().equals(target.getDateTime())) return i;
        }
        return -1;
    }

    private Order buildOrder(BacktestConfig config, Order.OrderType type,
                              double price, int quantity, double amount,
                              FeeCalculator.FeeDetail fee, LocalDateTime time, String remark) {
        return Order.builder()
                .orderId(UUID.randomUUID().toString())
                .stockCode(config.stockCode)
                .stockName(config.stockName)
                .orderType(type)
                .status(Order.OrderStatus.FILLED)
                .price(price).quantity(quantity)
                .filledPrice(price).filledQuantity(quantity)
                .amount(amount)
                .commission(fee.commission).stampTax(fee.stampTax)
                .transferFee(fee.transferFee).totalFee(fee.total)
                .createTime(time).filledTime(time)
                .remark(remark)
                .build();
    }
}


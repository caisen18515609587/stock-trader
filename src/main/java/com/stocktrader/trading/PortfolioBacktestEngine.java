package com.stocktrader.trading;

import com.stocktrader.analysis.StockAnalyzer;
import com.stocktrader.model.*;
import com.stocktrader.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 多标的组合回测引擎
 * <p>
 * 与单标的 BacktestEngine 的区别：
 *   - 统一管理多只股票，使用同一资金池
 *   - 每个交易日所有股票同步运行，资金在股票间竞争分配
 *   - 统计组合维度绩效：收益率、夏普比率、最大回撤、Calmar等
 *   - 支持多策略横向对比（compareStrategies 方法）
 */
public class PortfolioBacktestEngine {

    private static final Logger log = LoggerFactory.getLogger(PortfolioBacktestEngine.class);

    private final StockAnalyzer analyzer;
    private final FeeCalculator feeCalculator;

    public PortfolioBacktestEngine() {
        this.analyzer = new StockAnalyzer();
        this.feeCalculator = new FeeCalculator();
    }

    // ============================= 配置 =============================

    public static class PortfolioBacktestConfig {
        public final List<String> stockCodes;
        public final Map<String, String> stockNames;
        public final double initialCapital;
        public final LocalDate startDate;
        public final LocalDate endDate;
        public final double maxSinglePositionRatio;
        public final int maxPositions;
        public final int warmupBars;
        public final BacktestEngine.SlippageConfig slippage;

        public PortfolioBacktestConfig(List<String> stockCodes, Map<String, String> stockNames,
                                       double initialCapital, LocalDate startDate, LocalDate endDate,
                                       double maxSinglePositionRatio, int maxPositions,
                                       int warmupBars, BacktestEngine.SlippageConfig slippage) {
            this.stockCodes = new ArrayList<>(stockCodes);
            this.stockNames = new HashMap<>(stockNames);
            this.initialCapital = initialCapital;
            this.startDate = startDate;
            this.endDate = endDate;
            this.maxSinglePositionRatio = maxSinglePositionRatio;
            this.maxPositions = maxPositions;
            this.warmupBars = warmupBars;
            this.slippage = slippage != null ? slippage : BacktestEngine.SlippageConfig.defaultConfig();
        }

        /** 简化构造：默认滑点0.1%、单仓30%、最多持5只 */
        public PortfolioBacktestConfig(List<String> stockCodes, Map<String, String> stockNames,
                                       double initialCapital, LocalDate startDate, LocalDate endDate) {
            this(stockCodes, stockNames, initialCapital, startDate, endDate,
                    0.3, 5, 60, BacktestEngine.SlippageConfig.defaultConfig());
        }
    }

    // ============================= 组合回测结果 =============================

    public static class PortfolioBacktestResult {
        public final String strategyName;
        public final LocalDate startDate;
        public final LocalDate endDate;
        public final double initialCapital;
        public final double finalCapital;
        public final double totalReturn;
        public final double annualReturn;
        public final double maxDrawdown;
        public final double sharpeRatio;
        public final double calmarRatio;
        public final double winRate;
        public final double profitLossRatio;
        public final int totalTrades;
        public final double totalFee;
        public final double totalSlippage;
        /** 各标的已实现盈亏贡献 (code -> pnl) */
        public final Map<String, Double> stockContribution;
        /** 净值曲线 [日期索引, 总资产] */
        public final List<double[]> equityCurve;
        public final List<Order> allOrders;
        public final String slippageDesc;

        public PortfolioBacktestResult(String strategyName, LocalDate startDate, LocalDate endDate,
                                       double initialCapital, double finalCapital, double maxDrawdown,
                                       double winRate, double profitLossRatio, int totalTrades,
                                       double totalFee, double totalSlippage,
                                       Map<String, Double> stockContribution,
                                       List<double[]> equityCurve, List<double[]> dailyReturns,
                                       List<Order> allOrders, String slippageDesc) {
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
            this.totalFee = totalFee;
            this.totalSlippage = totalSlippage;
            this.stockContribution = stockContribution;
            this.equityCurve = equityCurve;
            this.allOrders = allOrders;
            this.slippageDesc = slippageDesc;

            long days = startDate.until(endDate).getDays();
            this.annualReturn = days > 0 ? totalReturn * 365.0 / days : totalReturn;
            this.sharpeRatio  = calcSharpe(dailyReturns, 0.03);
            this.calmarRatio  = maxDrawdown > 0 ? annualReturn / maxDrawdown : 0;
        }

        private static double calcSharpe(List<double[]> dailyReturns, double rfAnnual) {
            if (dailyReturns == null || dailyReturns.size() < 2) return 0;
            double rfd = rfAnnual / 252.0;
            double[] rets = dailyReturns.stream().mapToDouble(r -> r[0]).toArray();
            double mean = Arrays.stream(rets).average().orElse(0);
            double variance = Arrays.stream(rets).map(r -> (r - mean) * (r - mean)).average().orElse(0);
            double std = Math.sqrt(variance);
            return std == 0 ? 0 : (mean - rfd) / std * Math.sqrt(252);
        }

        public void print() {
            String sep  = "================================================================";
            String sepm = "----------------------------------------------------------------";
            System.out.println("\n" + sep);
            System.out.printf("【组合回测报告】策略：%s%n", strategyName);
            System.out.printf("时间：%s ~ %s   滑点：%s%n", startDate, endDate, slippageDesc);
            System.out.println(sepm);
            System.out.printf("初始资金：     %,.2f 元%n", initialCapital);
            System.out.printf("最终资金：     %,.2f 元%n", finalCapital);
            System.out.printf("组合总收益：   %+.2f%%%n", totalReturn);
            System.out.printf("年化收益率：   %+.2f%%%n", annualReturn);
            System.out.println(sepm);
            System.out.printf("最大回撤：     %.2f%%%n", maxDrawdown);
            System.out.printf("夏普比率：     %.3f%n", sharpeRatio);
            System.out.printf("Calmar比率：   %.3f%n", calmarRatio);
            System.out.printf("胜率：         %.1f%% (%d笔)%n", winRate, totalTrades);
            System.out.printf("盈亏比：       %.2f%n", profitLossRatio);
            System.out.printf("总手续费：     %,.2f 元%n", totalFee);
            System.out.printf("总滑点：       %,.2f 元%n", totalSlippage);
            System.out.printf("摩擦成本合计：  %,.2f 元%n", totalFee + totalSlippage);
            if (!stockContribution.isEmpty()) {
                System.out.println(sepm);
                System.out.println("各标的盈亏贡献：");
                stockContribution.entrySet().stream()
                        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                        .forEach(e -> System.out.printf("  %-8s  %+,.2f 元%n", e.getKey(), e.getValue()));
            }
            System.out.println(sep);
        }
    }

    // ============================= 多策略对比结果 =============================

    public static class StrategyComparisonResult {
        public final List<PortfolioBacktestResult> results;
        /** 夏普比率最高的策略 */
        public final PortfolioBacktestResult best;

        public StrategyComparisonResult(List<PortfolioBacktestResult> results) {
            this.results = new ArrayList<>(results);
            this.best = results.stream()
                    .max(Comparator.comparingDouble(r -> r.sharpeRatio))
                    .orElse(results.isEmpty() ? null : results.get(0));
        }

        public void print() {
            String sep = "================================================================";
            System.out.println("\n" + sep);
            System.out.println("【多策略组合对比汇总】");
            System.out.printf("%-24s %8s %8s %8s %7s %7s %7s%n",
                    "策略", "总收益%", "年化%", "最大回撤%", "夏普", "胜率%", "Calmar");
            System.out.println("------------------------------------------------------------------------");
            for (PortfolioBacktestResult r : results) {
                String mark = r == best ? " ★最优" : "";
                System.out.printf("%-24s %+7.2f%% %+7.2f%% %7.2f%%  %6.3f %7.1f%% %6.3f%s%n",
                        r.strategyName, r.totalReturn, r.annualReturn,
                        r.maxDrawdown, r.sharpeRatio, r.winRate, r.calmarRatio, mark);
            }
            System.out.println(sep);
            if (best != null) System.out.printf("最优策略（按夏普）：%s%n", best.strategyName);
        }
    }

    // ============================= 核心回测逻辑 =============================

    /**
     * 执行组合回测
     * @param config       组合配置
     * @param stockBarsMap 各股票全量K线 (code -> bars 升序)
     * @param strategy     交易策略
     */
    public PortfolioBacktestResult run(PortfolioBacktestConfig config,
                                       Map<String, List<StockBar>> stockBarsMap,
                                       TradingStrategy strategy) {
        log.info("组合回测开始：{} 只股票  策略：{}  滑点：{}",
                config.stockCodes.size(), strategy.getStrategyName(), config.slippage);

        // 过滤回测区间
        Map<String, List<StockBar>> backtestBars = new LinkedHashMap<>();
        for (String code : config.stockCodes) {
            List<StockBar> bars = stockBarsMap.get(code);
            if (bars == null || bars.isEmpty()) continue;
            List<StockBar> filtered = bars.stream()
                    .filter(b -> b.getDate() != null
                            && !b.getDate().isBefore(config.startDate)
                            && !b.getDate().isAfter(config.endDate))
                    .collect(Collectors.toList());
            if (!filtered.isEmpty()) backtestBars.put(code, filtered);
        }
        if (backtestBars.isEmpty()) { log.warn("组合回测：没有有效数据"); return null; }

        // 合并时间轴（所有股票出现过的交易日）
        Set<LocalDate> allDatesSet = new TreeSet<>();
        backtestBars.values().forEach(bars -> bars.forEach(b -> allDatesSet.add(b.getDate())));
        List<LocalDate> allDates = new ArrayList<>(allDatesSet);

        // 账户状态
        double cash = config.initialCapital;
        Map<String, Integer> positions  = new LinkedHashMap<>();  // code -> 持有股数
        Map<String, Double>  avgCosts   = new LinkedHashMap<>();  // code -> 均价
        Map<String, Double>  stockPnl   = new LinkedHashMap<>();  // code -> 已实现PnL
        List<Order> allOrders           = new ArrayList<>();
        List<double[]> equityCurve      = new ArrayList<>();
        List<double[]> dailyReturns     = new ArrayList<>();
        List<Double> tradePnls          = new ArrayList<>();

        double maxAssets     = config.initialCapital;
        double maxDrawdown   = 0;
        double totalFee      = 0;
        double totalSlippage = 0;
        double prevAssets    = config.initialCapital;

        // 各股票当日最新Bar缓存（随时间推进更新）
        Map<String, StockBar> latestBar = new HashMap<>();

        for (int dateIdx = 0; dateIdx < allDates.size(); dateIdx++) {
            LocalDate date = allDates.get(dateIdx);

            // 更新当日各股票最新bar
            for (Map.Entry<String, List<StockBar>> entry : backtestBars.entrySet()) {
                String code = entry.getKey();
                entry.getValue().stream()
                        .filter(b -> date.equals(b.getDate()))
                        .findFirst()
                        .ifPresent(b -> latestBar.put(code, b));
            }

            // 对每只股票运行策略
            for (String code : config.stockCodes) {
                StockBar todayBar = latestBar.get(code);
                if (todayBar == null) continue;

                List<StockBar> fullBars = stockBarsMap.get(code);
                if (fullBars == null) continue;

                // 截止今日的历史K线（含预热）
                final LocalDate today = date;
                List<StockBar> hist = fullBars.stream()
                        .filter(b -> b.getDate() != null && !b.getDate().isAfter(today))
                        .collect(Collectors.toList());

                if (hist.size() < strategy.getMinBarsRequired() + config.warmupBars) continue;

                String name = config.stockNames.getOrDefault(code, code);
                boolean hasPos = positions.getOrDefault(code, 0) > 0;

                // 构造虚拟Portfolio供策略判断（只关心是否有持仓）
                Portfolio fakePortfolio = buildFakePortfolio(
                        code, name, cash, positions, avgCosts, latestBar, config);

                AnalysisResult analysisResult = analyzer.analyze(code, name, hist);
                TradeSignal signal = strategy.generateSignal(
                        code, name, hist, analysisResult, fakePortfolio);

                double signalPrice = todayBar.getClose();
                long avgVol = calcAvgVolume(fullBars, 20);

                // ---- 买入 ----
                if (signal.getSignalType().isBuySignal() && !hasPos
                        && positions.size() < config.maxPositions) {
                    double posRatio = Math.min(
                            strategy.calculatePositionSize(signal, fakePortfolio),
                            config.maxSinglePositionRatio);
                    double buyAmt = cash * posRatio;
                    int qty = (int) (buyAmt / signalPrice / 100) * 100;
                    if (qty == 0 && cash >= signalPrice * 100) {
                        qty = (int) (cash / signalPrice / 100) * 100;
                    }
                    if (qty > 0) {
                        double execPrice = config.slippage.apply(signalPrice, true, qty, avgVol);
                        double slip = (execPrice - signalPrice) * qty;
                        double amt  = execPrice * qty;
                        String exch = code.startsWith("6") ? "SH" : "SZ";
                        FeeCalculator.FeeDetail fee = feeCalculator.calculateBuyFee(amt, exch);
                        double cost = amt + fee.total;
                        if (cash >= cost) {
                            cash -= cost;
                            positions.put(code, qty);
                            avgCosts.put(code, execPrice);
                            totalFee     += fee.total;
                            totalSlippage += slip;
                            stockPnl.putIfAbsent(code, 0.0);
                            allOrders.add(buildOrder(code, name, Order.OrderType.BUY,
                                    execPrice, qty, amt, fee, date.atStartOfDay(), signal.getReason()));
                    log.debug("[{}][{}] 买入{}股 @{}(滑点{})",
                            date, code, qty,
                            String.format("%.3f", execPrice),
                            String.format("%.2f", slip));
                        }
                    }
                }

                // ---- 卖出 ----
                else if (signal.getSignalType().isSellSignal() && hasPos) {
                    int qty = positions.get(code);
                    if (signal.getSignalType() == TradeSignal.SignalType.TAKE_PROFIT) {
                        qty = Math.max((qty / 2 / 100) * 100, 100);
                    }
                    if (qty <= 0) continue;

                    double execPrice = config.slippage.apply(signalPrice, false, qty, avgVol);
                    double slip = (signalPrice - execPrice) * qty;
                    double amt  = execPrice * qty;
                    String exch = code.startsWith("6") ? "SH" : "SZ";
                    FeeCalculator.FeeDetail fee = feeCalculator.calculateSellFee(amt, exch);

                    double costBasis = avgCosts.getOrDefault(code, execPrice);
                    double pnl = (execPrice - costBasis) * qty - fee.total - slip;
                    tradePnls.add(pnl);
                    stockPnl.merge(code, pnl, Double::sum);

                    cash += amt - fee.total;
                    int remain = positions.get(code) - qty;
                    if (remain <= 0) { positions.remove(code); avgCosts.remove(code); }
                    else { positions.put(code, remain); }
                    totalFee     += fee.total;
                    totalSlippage += slip;
                    allOrders.add(buildOrder(code, name, Order.OrderType.SELL,
                            execPrice, qty, amt, fee, date.atStartOfDay(), signal.getReason()));
                    log.debug("[{}][{}] 卖出{}股 @{} PnL={}",
                            date, code, qty,
                            String.format("%.3f", execPrice),
                            String.format("%.2f", pnl));
                }
            }

            // 当日收盘总资产
            double posValue = 0;
            for (Map.Entry<String, Integer> e : positions.entrySet()) {
                StockBar bar = latestBar.get(e.getKey());
                if (bar != null && e.getValue() > 0) posValue += bar.getClose() * e.getValue();
            }
            double total = cash + posValue;
            equityCurve.add(new double[]{dateIdx, total});

            if (prevAssets > 0) dailyReturns.add(new double[]{(total - prevAssets) / prevAssets});
            prevAssets = total;

            // 最大回撤
            maxAssets = Math.max(maxAssets, total);
            double dd = maxAssets > 0 ? (maxAssets - total) / maxAssets * 100 : 0;
            maxDrawdown = Math.max(maxDrawdown, dd);
        }

        // 末尾未平仓视为按最后价卖出
        for (Map.Entry<String, Integer> e : positions.entrySet()) {
            String code = e.getKey();
            StockBar bar = latestBar.get(code);
            if (bar != null && e.getValue() > 0) {
                double lp  = bar.getClose();
                double pnl = (lp - avgCosts.getOrDefault(code, lp)) * e.getValue();
                tradePnls.add(pnl);
                stockPnl.merge(code, pnl, Double::sum);
            }
        }

        // 最终总资产（包含未平仓浮盈）
        double finalTotal = cash;
        for (Map.Entry<String, Integer> e : positions.entrySet()) {
            StockBar bar = latestBar.get(e.getKey());
            if (bar != null && e.getValue() > 0) finalTotal += bar.getClose() * e.getValue();
        }

        // 统计
        int winTrades   = (int) tradePnls.stream().filter(p -> p > 0).count();
        int totalTrades = tradePnls.size();
        double winRate  = totalTrades > 0 ? (double) winTrades / totalTrades * 100 : 0;
        double avgWin   = tradePnls.stream().filter(p -> p > 0).mapToDouble(Double::doubleValue).average().orElse(0);
        double avgLoss  = Math.abs(tradePnls.stream().filter(p -> p < 0).mapToDouble(Double::doubleValue).average().orElse(-1));
        double plRatio  = avgLoss > 0 ? avgWin / avgLoss : avgWin;

        log.info("组合回测完成：{}  收益{}%  夏普(预算)  最大回撤{}%  总滑点{}",
                strategy.getStrategyName(),
                String.format("%.2f", (finalTotal - config.initialCapital) / config.initialCapital * 100),
                String.format("%.2f", maxDrawdown),
                String.format("%.2f", totalSlippage));

        return new PortfolioBacktestResult(
                strategy.getStrategyName(), config.startDate, config.endDate,
                config.initialCapital, finalTotal, maxDrawdown,
                winRate, plRatio, totalTrades, totalFee, totalSlippage,
                stockPnl, equityCurve, dailyReturns, allOrders,
                config.slippage.toString());
    }

    /**
     * 多策略对比：对同一组标的运行多个策略，汇总比较
     * @param config       组合配置
     * @param stockBarsMap 各股票全量K线
     * @param strategies   待比较策略列表
     */
    public StrategyComparisonResult compareStrategies(PortfolioBacktestConfig config,
                                                      Map<String, List<StockBar>> stockBarsMap,
                                                      List<TradingStrategy> strategies) {
        List<PortfolioBacktestResult> results = new ArrayList<>();
        for (TradingStrategy s : strategies) {
            PortfolioBacktestResult r = run(config, stockBarsMap, s);
            if (r != null) results.add(r);
        }
        StrategyComparisonResult cmp = new StrategyComparisonResult(results);
        cmp.print();
        return cmp;
    }

    // ============================= 辅助 =============================

    private long calcAvgVolume(List<StockBar> bars, int n) {
        int size = bars.size();
        if (size == 0) return 0;
        int from = Math.max(0, size - n);
        long sum = 0;
        for (int i = from; i < size; i++) sum += bars.get(i).getVolume();
        int cnt = size - from;
        return cnt > 0 ? sum / cnt : 0;
    }

    /**
     * 构造轻量虚拟Portfolio，让策略判断是否有持仓
     */
    private Portfolio buildFakePortfolio(String targetCode, String targetName,
                                          double cash,
                                          Map<String, Integer> positions,
                                          Map<String, Double> avgCosts,
                                          Map<String, StockBar> latestBar,
                                          PortfolioBacktestConfig config) {
        double posValue = 0;
        for (Map.Entry<String, Integer> e : positions.entrySet()) {
            StockBar bar = latestBar.get(e.getKey());
            if (bar != null && e.getValue() > 0) posValue += bar.getClose() * e.getValue();
        }
        Portfolio p = new Portfolio(
                "portfolio_backtest",
                "组合回测账户",
                cash + posValue,
                Portfolio.AccountMode.PAPER);
        // 只注入当前标的的持仓，让策略感知
        if (positions.containsKey(targetCode)) {
            int qty = positions.get(targetCode);
            double cost = avgCosts.getOrDefault(targetCode, 0.0);
            StockBar bar = latestBar.get(targetCode);
            double price = bar != null ? bar.getClose() : cost;
            if (qty > 0) {
                // 通过买入订单模拟注入持仓
                Order fakeOrder = Order.builder()
                        .orderId("fake_" + targetCode)
                        .stockCode(targetCode).stockName(targetName)
                        .orderType(Order.OrderType.BUY)
                        .status(Order.OrderStatus.FILLED)
                        .price(cost).quantity(qty)
                        .filledPrice(cost).filledQuantity(qty)
                        .amount(cost * qty)
                        .commission(0).stampTax(0).transferFee(0).totalFee(0)
                        .createTime(LocalDateTime.now()).filledTime(LocalDateTime.now())
                        .remark("fake")
                        .build();
                p.executeBuy(fakeOrder);
                p.updatePositionPrice(targetCode, price);
            }
        }
        return p;
    }

    private Order buildOrder(String code, String name, Order.OrderType type,
                              double price, int quantity, double amount,
                              FeeCalculator.FeeDetail fee, LocalDateTime time, String remark) {
        return Order.builder()
                .orderId(UUID.randomUUID().toString())
                .stockCode(code).stockName(name)
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


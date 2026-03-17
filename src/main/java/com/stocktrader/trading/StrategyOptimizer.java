package com.stocktrader.trading;

import com.stocktrader.model.StockBar;
import com.stocktrader.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 策略参数优化器 + Walk-Forward 前向验证
 * <p>
 * 一、网格搜索（Grid Search）
 *   遍历参数空间中所有组合，对每个参数组合执行回测，以夏普比率（或其他指标）为目标函数，
 *   选出最优参数。支持多线程并行加速。
 * <p>
 * 二、Walk-Forward 前向验证（滚动窗口法）
 *   将回测区间分成多个「训练窗口 + 测试窗口」的滚动片段：
 *     - In-Sample (IS)：用来做参数优化，找到最优参数
 *     - Out-of-Sample (OOS)：用最优参数在OOS段进行前向测试
 *   拼接所有OOS段的结果，得到更真实的策略表现评估（避免过拟合）。
 * <p>
 * 使用流程：
 * <pre>
 *   // 1. 定义参数空间
 *   Map&lt;String,double[]&gt; paramGrid = new LinkedHashMap&lt;&gt;();
 *   paramGrid.put("stopLoss",     new double[]{0.03, 0.05, 0.07});
 *   paramGrid.put("takeProfit",   new double[]{0.08, 0.12, 0.18});
 *
 *   // 2. 定义参数 -> 策略的构造函数
 *   Function&lt;Map&lt;String,Double&gt;, TradingStrategy&gt; factory = params -> {
 *       double sl = params.get("stopLoss");
 *       double tp = params.get("takeProfit");
 *       return new MediumLongStrategy(sl, tp);
 *   };
 *
 *   // 3. 执行 Walk-Forward
 *   StrategyOptimizer optimizer = new StrategyOptimizer();
 *   WalkForwardResult wfResult = optimizer.walkForward(
 *       stockCode, stockName, allBars,
 *       LocalDate.of(2022,1,1), LocalDate.of(2025,1,1),
 *       240, 60,        // 训练240天 + 测试60天
 *       paramGrid, factory, OptimizeTarget.SHARPE,
 *       BacktestEngine.SlippageConfig.defaultConfig(), 100000
 *   );
 *   wfResult.print();
 * </pre>
 */
public class StrategyOptimizer {

    private static final Logger log = LoggerFactory.getLogger(StrategyOptimizer.class);

    private final BacktestEngine backtestEngine;
    /** 并发线程数（默认 CPU 核数 - 1，最少1） */
    private final int threads;

    public StrategyOptimizer() {
        this.backtestEngine = new BacktestEngine();
        this.threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    public StrategyOptimizer(int threads) {
        this.backtestEngine = new BacktestEngine();
        this.threads = Math.max(1, threads);
    }

    // ============================= 优化目标 =============================

    public enum OptimizeTarget {
        SHARPE,       // 夏普比率（默认）
        TOTAL_RETURN, // 总收益率
        CALMAR,       // Calmar = 年化收益 / 最大回撤
        WIN_RATE,     // 胜率
        PROFIT_LOSS   // 盈亏比
    }

    /** 从回测结果中提取目标值 */
    private double extractTarget(BacktestEngine.BacktestResult r, OptimizeTarget target) {
        if (r == null) return Double.NEGATIVE_INFINITY;
        switch (target) {
            case SHARPE:       return r.sharpeRatio;
            case TOTAL_RETURN: return r.totalReturn;
            case CALMAR:       return r.calmarRatio;
            case WIN_RATE:     return r.winRate;
            case PROFIT_LOSS:  return r.profitLossRatio;
            default:           return r.sharpeRatio;
        }
    }

    // ============================= 参数组合 =============================

    /** 单次参数优化结果 */
    public static class ParamOptResult implements Comparable<ParamOptResult> {
        public final Map<String, Double> params;
        public final BacktestEngine.BacktestResult backtestResult;
        public final double score;  // 目标函数值

        public ParamOptResult(Map<String, Double> params,
                              BacktestEngine.BacktestResult backtestResult, double score) {
            this.params = params;
            this.backtestResult = backtestResult;
            this.score = score;
        }

        @Override
        public int compareTo(ParamOptResult o) {
            return Double.compare(o.score, this.score); // 降序
        }
    }

    // ============================= 网格搜索 =============================

    /**
     * 网格搜索参数优化
     * @param stockCode  股票代码
     * @param stockName  股票名称
     * @param allBars    全量K线
     * @param startDate  回测开始日期
     * @param endDate    回测结束日期
     * @param paramGrid  参数空间（name -> 候选值数组）
     * @param factory    参数 -> 策略的构造器
     * @param target     优化目标
     * @param slippage   滑点配置
     * @param capital    初始资金
     * @param topN       返回前N个最优结果
     * @return 按目标值降序排列的参数优化结果
     */
    public List<ParamOptResult> gridSearch(String stockCode, String stockName,
                                            List<StockBar> allBars,
                                            LocalDate startDate, LocalDate endDate,
                                            Map<String, double[]> paramGrid,
                                            Function<Map<String, Double>, TradingStrategy> factory,
                                            OptimizeTarget target,
                                            BacktestEngine.SlippageConfig slippage,
                                            double capital, int topN) {
        List<Map<String, Double>> allCombinations = generateCombinations(paramGrid);
        log.info("网格搜索：{} 种参数组合，使用 {} 线程并行", allCombinations.size(), threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ParamOptResult>> futures = new ArrayList<>();

        for (Map<String, Double> params : allCombinations) {
            futures.add(pool.submit(() -> {
                try {
                    TradingStrategy strategy = factory.apply(params);
                    BacktestEngine.BacktestConfig cfg = new BacktestEngine.BacktestConfig(
                            stockCode, stockName, capital, startDate, endDate, 0.5, 60, slippage);
                    BacktestEngine engine = new BacktestEngine();
                    BacktestEngine.BacktestResult r = engine.run(cfg, allBars, strategy);
                    double score = extractTarget(r, target);
                    return new ParamOptResult(new LinkedHashMap<>(params), r, score);
                } catch (Exception e) {
                    log.warn("参数组合运行出错：{} - {}", params, e.getMessage());
                    return new ParamOptResult(new LinkedHashMap<>(params), null, Double.NEGATIVE_INFINITY);
                }
            }));
        }

        pool.shutdown();
        try { pool.awaitTermination(10, TimeUnit.MINUTES); } catch (InterruptedException ignored) {}

        List<ParamOptResult> results = new ArrayList<>();
        for (Future<ParamOptResult> f : futures) {
            try { results.add(f.get()); } catch (Exception ignored) {}
        }

        results.sort(ParamOptResult::compareTo);
        log.info("网格搜索完成，最优参数 score={}：{}",
                String.format("%.4f", results.isEmpty() ? 0.0 : results.get(0).score),
                results.isEmpty() ? "{}" : results.get(0).params);

        return results.stream().limit(topN).collect(Collectors.toList());
    }

    // ============================= Walk-Forward 前向验证 =============================

    /** Walk-Forward 单个窗口结果 */
    public static class WalkForwardWindow {
        public final int windowIndex;
        public final LocalDate isStart;     // In-Sample 开始
        public final LocalDate isEnd;       // In-Sample 结束
        public final LocalDate oosStart;    // Out-of-Sample 开始
        public final LocalDate oosEnd;      // Out-of-Sample 结束
        public final Map<String, Double> bestParams;     // IS最优参数
        public final double isScore;                     // IS最优目标值
        public final BacktestEngine.BacktestResult oosResult;  // OOS回测结果

        public WalkForwardWindow(int windowIndex, LocalDate isStart, LocalDate isEnd,
                                 LocalDate oosStart, LocalDate oosEnd,
                                 Map<String, Double> bestParams, double isScore,
                                 BacktestEngine.BacktestResult oosResult) {
            this.windowIndex = windowIndex;
            this.isStart = isStart;
            this.isEnd = isEnd;
            this.oosStart = oosStart;
            this.oosEnd = oosEnd;
            this.bestParams = bestParams;
            this.isScore = isScore;
            this.oosResult = oosResult;
        }
    }

    /** Walk-Forward 完整验证结果 */
    public static class WalkForwardResult {
        public final String stockCode;
        public final String stockName;
        public final String optimizeTargetName;
        public final List<WalkForwardWindow> windows;

        /** 合并所有OOS交易后的综合统计 */
        public final double combinedOosReturn;    // OOS累计收益率
        public final double avgOosSharpe;         // 平均OOS夏普
        public final double avgOosDrawdown;       // 平均OOS最大回撤
        public final double oosWinRate;           // OOS胜率（窗口维度：赚钱窗口数/总窗口数）
        /** IS vs OOS 衰减率（越接近1越好，说明没有过拟合） */
        public final double isOosDecayRatio;

        public WalkForwardResult(String stockCode, String stockName,
                                 String optimizeTargetName, List<WalkForwardWindow> windows) {
            this.stockCode = stockCode;
            this.stockName = stockName;
            this.optimizeTargetName = optimizeTargetName;
            this.windows = windows;

            // 统计
            double totalOosReturn = 0;
            double sumSharpe = 0, sumDrawdown = 0;
            int validCount = 0, profitWindows = 0;
            double sumIsScore = 0, sumOosScore = 0;

            for (WalkForwardWindow w : windows) {
                if (w.oosResult == null) continue;
                totalOosReturn += w.oosResult.totalReturn;
                sumSharpe    += w.oosResult.sharpeRatio;
                sumDrawdown  += w.oosResult.maxDrawdown;
                if (w.oosResult.totalReturn > 0) profitWindows++;
                sumIsScore  += w.isScore;
                sumOosScore += w.oosResult.sharpeRatio;
                validCount++;
            }

            this.combinedOosReturn = totalOosReturn;
            this.avgOosSharpe  = validCount > 0 ? sumSharpe  / validCount : 0;
            this.avgOosDrawdown = validCount > 0 ? sumDrawdown / validCount : 0;
            this.oosWinRate    = validCount > 0 ? (double) profitWindows / validCount * 100 : 0;
            // IS/OOS Decay：OOS夏普均值 / IS夏普均值
            double avgIs  = validCount > 0 ? sumIsScore  / validCount : 1;
            double avgOos = validCount > 0 ? sumOosScore / validCount : 0;
            this.isOosDecayRatio = (avgIs != 0) ? avgOos / avgIs : 0;
        }

        public void print() {
            String sep  = "================================================================";
            String sepm = "----------------------------------------------------------------";
            System.out.println("\n" + sep);
            System.out.printf("【Walk-Forward 前向验证报告】%s %s%n", stockCode, stockName);
            System.out.printf("优化目标：%s   共 %d 个滚动窗口%n",
                    optimizeTargetName, windows.size());
            System.out.println(sepm);
            System.out.printf("%-4s  %-10s~%-10s  %-10s~%-10s  %-12s  %7s  %7s  %7s%n",
                    "#", "IS开始", "IS结束", "OOS开始", "OOS结束", "最优参数(摘要)",
                    "IS得分", "OOS收益", "OOS夏普");
            for (WalkForwardWindow w : windows) {
                String paramSummary = w.bestParams.entrySet().stream()
                        .map(e -> e.getKey().substring(0, Math.min(4, e.getKey().length()))
                                + "=" + String.format("%.4f", e.getValue()))
                        .collect(Collectors.joining(","));
                String oosRet   = w.oosResult != null ? String.format("%+.2f%%", w.oosResult.totalReturn) : "N/A";
                String oosSh    = w.oosResult != null ? String.format("%.3f", w.oosResult.sharpeRatio)    : "N/A";
                System.out.printf("%-4d  %-10s~%-10s  %-10s~%-10s  %-12s  %7.4f  %7s  %7s%n",
                        w.windowIndex, w.isStart, w.isEnd, w.oosStart, w.oosEnd,
                        paramSummary.length() > 12 ? paramSummary.substring(0, 12) : paramSummary,
                        w.isScore, oosRet, oosSh);
            }
            System.out.println(sepm);
            System.out.printf("OOS累计收益：    %+.2f%%%n",  combinedOosReturn);
            System.out.printf("OOS平均夏普：    %.3f%n",     avgOosSharpe);
            System.out.printf("OOS平均最大回撤：%.2f%%%n",   avgOosDrawdown);
            System.out.printf("OOS盈利窗口率：  %.1f%%%n",   oosWinRate);
            System.out.printf("IS/OOS衰减比：  %.3f  (>0.5说明OOS保留了IS大部分表现)%n", isOosDecayRatio);
            System.out.println(sep);
        }
    }

    /**
     * Walk-Forward 前向验证（滚动窗口）
     * @param stockCode     股票代码
     * @param stockName     股票名称
     * @param allBars       全量K线（含IS+OOS全部数据）
     * @param totalStart    整体回测开始日期
     * @param totalEnd      整体回测结束日期
     * @param isDays        In-Sample 天数（如240）
     * @param oosDays       Out-of-Sample 天数（如60）
     * @param paramGrid     参数空间
     * @param factory       参数 -> 策略构造器
     * @param target        优化目标
     * @param slippage      滑点配置
     * @param capital       初始资金
     * @return Walk-Forward 验证结果
     */
    public WalkForwardResult walkForward(String stockCode, String stockName,
                                          List<StockBar> allBars,
                                          LocalDate totalStart, LocalDate totalEnd,
                                          int isDays, int oosDays,
                                          Map<String, double[]> paramGrid,
                                          Function<Map<String, Double>, TradingStrategy> factory,
                                          OptimizeTarget target,
                                          BacktestEngine.SlippageConfig slippage,
                                          double capital) {
        log.info("Walk-Forward 开始：{} {}  IS={}天 OOS={}天", stockCode, stockName, isDays, oosDays);

        List<WalkForwardWindow> windows = new ArrayList<>();
        int windowIdx = 0;
        LocalDate winStart = totalStart;

        while (true) {
            LocalDate isStart = winStart;
            LocalDate isEnd   = isStart.plusDays(isDays);
            LocalDate oosStart = isEnd.plusDays(1);
            LocalDate oosEnd   = oosStart.plusDays(oosDays);

            if (oosEnd.isAfter(totalEnd)) break;

            log.info("[窗口{}] IS: {} ~ {}  OOS: {} ~ {}",
                    windowIdx, isStart, isEnd, oosStart, oosEnd);

            // IS阶段：网格搜索最优参数
            List<ParamOptResult> isResults = gridSearch(
                    stockCode, stockName, allBars, isStart, isEnd,
                    paramGrid, factory, target, slippage, capital, 1);

            Map<String, Double> bestParams = isResults.isEmpty()
                    ? new LinkedHashMap<>() : isResults.get(0).params;
            double isScore = isResults.isEmpty() ? Double.NaN : isResults.get(0).score;

            // OOS阶段：用IS最优参数前向测试
            BacktestEngine.BacktestResult oosResult = null;
            if (!bestParams.isEmpty()) {
                try {
                    TradingStrategy oosStrategy = factory.apply(bestParams);
                    BacktestEngine.BacktestConfig oosCfg = new BacktestEngine.BacktestConfig(
                            stockCode, stockName, capital, oosStart, oosEnd, 0.5, 60, slippage);
                    oosResult = backtestEngine.run(oosCfg, allBars, oosStrategy);
                } catch (Exception e) {
                    log.warn("[窗口{}] OOS回测出错：{}", windowIdx, e.getMessage());
                }
            }

            windows.add(new WalkForwardWindow(windowIdx, isStart, isEnd,
                    oosStart, oosEnd, bestParams, isScore, oosResult));

            // 滚动到下一窗口（步长 = OOS天数）
            winStart = winStart.plusDays(oosDays);
            windowIdx++;

            // 防止无限循环
            if (windowIdx > 50) { log.warn("Walk-Forward 窗口数超限(50)，终止"); break; }
        }

        WalkForwardResult result = new WalkForwardResult(
                stockCode, stockName, target.name(), windows);

        log.info("Walk-Forward 完成：{} 个窗口  OOS累计收益{}%  IS/OOS衰减={}",
                windows.size(),
                String.format("%.2f", result.combinedOosReturn),
                String.format("%.3f", result.isOosDecayRatio));

        return result;
    }

    // ============================= 工具方法 =============================

    /**
     * 将参数空间展开为所有组合（笛卡尔积）
     */
    static List<Map<String, Double>> generateCombinations(Map<String, double[]> paramGrid) {
        List<String> keys = new ArrayList<>(paramGrid.keySet());
        List<Map<String, Double>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>());

        for (String key : keys) {
            double[] values = paramGrid.get(key);
            List<Map<String, Double>> expanded = new ArrayList<>();
            for (Map<String, Double> existing : result) {
                for (double v : values) {
                    Map<String, Double> copy = new LinkedHashMap<>(existing);
                    copy.put(key, v);
                    expanded.add(copy);
                }
            }
            result = expanded;
        }
        return result;
    }
}


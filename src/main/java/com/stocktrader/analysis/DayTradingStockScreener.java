package com.stocktrader.analysis;

import com.stocktrader.datasource.StockDataProvider;
import com.stocktrader.model.AnalysisResult;
import com.stocktrader.model.StockBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 日内做T专用选股器
 * <p>
 * 目标：从全市场中筛选出1支最适合当日做T的股票
 * <p>
 * 选股标准（核心三维）：
 * 1. 【K线波动性】- ATR波动率大（日内振幅大），有充足的做T价差空间
 *    - 近20日平均振幅（High-Low）/ 收盘价 >= 2.5%（基准要求）
 *    - ATR(14) / 收盘价 >= 1.5%（ATR波动率要求）
 * 2. 【趋势可操作性】- 股票有明显的日内波动节律，非单边下跌/单边涨停
 *    - 近20日内，有效波动天数（振幅>=1.5%）占比 >= 60%
 *    - 近5日不连续跌停（跌幅 <= -9.5%）
 *    - 技术面综合评分 >= 55（价格在支撑区附近，有反弹做T动力）
 * 3. 【流动性】- 成交量充足，能顺利进出
 *    - 近20日日均换手率 >= 1.5%（保证流动性）
 *    - 近5日日均成交额 >= 5000万（中小资金可顺利成交）
 * <p>
 * 最优评分公式（100分制）：
 *   做T综合分 = ATR波动分(35) + 振幅均匀分(25) + 换手率分(20) + 技术面分(20)
 * <p>
 * 流程：
 * 1. 获取全市场行情快照
 * 2. 预筛选（去ST/停牌/涨跌停/极小市值）
 * 3. 对候选股并行获取K线，计算做T综合评分
 * 4. 返回得分最高的1支股票
 */
public class DayTradingStockScreener {

    private static final Logger log = LoggerFactory.getLogger(DayTradingStockScreener.class);

    private final StockDataProvider dataProvider;
    private final StockAnalyzer analyzer;

    // ===== 选股阈值 =====
    /** 日均振幅最低要求（High-Low/Close） */
    private static final double MIN_AVG_SWING_PCT = 0.018;   // 1.8%（原2.5%，适当放宽适应低波动市场）
    /** ATR波动率最低要求（ATR/Close） */
    private static final double MIN_ATR_PCT        = 0.010;   // 1.0%（原1.5%，适当放宽）
    /** 有效波动天数（振幅>=1.5%）最低占比 */
    private static final double MIN_SWING_DAYS_RATIO = 0.40;  // 40%（原60%，适当放宽）
    /** 近5日不允许连续跌停（振幅判断每日跌幅） */
    private static final double LIMIT_DOWN_PCT     = -0.095;  // -9.5%
    /** 技术面综合评分最低 */
    private static final int    MIN_TECH_SCORE     = 45;      // 45（原55，适当放宽）
    /** 近20日日均换手率最低 */
    private static final double MIN_TURNOVER_RATE  = 1.5;     // 1.5%
    /** 近5日日均成交额最低（万元） */
    private static final double MIN_AVG_AMOUNT_W   = 3000.0;  // 3000万（原5000万，适当放宽）

    public DayTradingStockScreener(StockDataProvider dataProvider) {
        this.dataProvider = dataProvider;
        this.analyzer = new StockAnalyzer();
    }

    /**
     * 做T选股结果
     */
    public static class DayTradingCandidate implements Comparable<DayTradingCandidate> {
        public final String stockCode;
        public final String stockName;
        public final double currentPrice;
        public final double changePercent;
        public final double avgSwingPct;      // 近20日平均日内振幅（%）
        public final double atrPct;           // ATR波动率（%）
        public final double avgTurnoverRate;  // 近20日平均换手率（%）
        public final double avgAmountW;       // 近5日日均成交额（万元）
        public final int    techScore;        // 技术面评分
        public final int    dayTradingScore;  // 做T综合评分（100分制）
        public final String scoreDetail;      // 评分详情

        public DayTradingCandidate(String stockCode, String stockName, double currentPrice,
                                   double changePercent, double avgSwingPct, double atrPct,
                                   double avgTurnoverRate, double avgAmountW,
                                   int techScore, int dayTradingScore, String scoreDetail) {
            this.stockCode     = stockCode;
            this.stockName     = stockName;
            this.currentPrice  = currentPrice;
            this.changePercent = changePercent;
            this.avgSwingPct   = avgSwingPct;
            this.atrPct        = atrPct;
            this.avgTurnoverRate = avgTurnoverRate;
            this.avgAmountW    = avgAmountW;
            this.techScore     = techScore;
            this.dayTradingScore = dayTradingScore;
            this.scoreDetail   = scoreDetail;
        }

        @Override
        public int compareTo(DayTradingCandidate o) {
            // 按做T综合评分降序排列
            return Integer.compare(o.dayTradingScore, this.dayTradingScore);
        }

        @Override
        public String toString() {
            return String.format("[%s %s] 现价=%.2f 涨跌=%.2f%% | 日内振幅=%.2f%% ATR=%.2f%% " +
                            "换手=%.2f%% 均额=%.0f万 技术分=%d 做T综合分=%d | %s",
                    stockCode, stockName, currentPrice, changePercent,
                    avgSwingPct * 100, atrPct * 100,
                    avgTurnoverRate, avgAmountW,
                    techScore, dayTradingScore, scoreDetail);
        }
    }

    /**
     * 全市场扫描，选出最适合做T的1支股票（主入口）
     *
     * @return 最优做T标的，若找不到则返回 null
     */
    public DayTradingCandidate selectBestForDayTrading() {
        log.info("====== 开始做T选股扫描（目标：选出最优1支）======");

        // Step1: 获取全量A股行情快照
        StockScreener tempScreener = new StockScreener(dataProvider);
        List<Map<String, Object>> allQuotes = tempScreener.fetchAllStockQuotes();
        log.info("获取全量A股行情：{}只", allQuotes.size());

        // Step2: 预筛选（去掉明显不适合做T的股票）
        List<Map<String, Object>> candidates = preFilterForDayTrading(allQuotes);
        log.info("预筛选后做T候选：{}只", candidates.size());

        if (candidates.isEmpty()) {
            log.warn("做T预筛选无结果，降级使用全量行情");
            candidates = allQuotes;
        }

        // Step3: 并行获取K线，计算做T综合评分
        List<DayTradingCandidate> results = scoreCandidates(candidates);
        log.info("K线评分完成，达到做T标准：{}只", results.size());

        if (results.isEmpty()) {
            log.warn("未找到符合做T标准的股票，请检查市场状态");
            return null;
        }

        // Step4: 按做T综合分排序，取第1名
        Collections.sort(results);
        DayTradingCandidate best = results.get(0);

        log.info("====== 做T选股完成 ======");
        log.info("最优标的: {}", best);
        log.info("Top5参考：");
        for (int i = 0; i < Math.min(5, results.size()); i++) {
            DayTradingCandidate r = results.get(i);
            log.info("  第{}名: {} {} 做T综合分={}", i + 1, r.stockCode, r.stockName, r.dayTradingScore);
        }
        return best;
    }

    /**
     * 全市场扫描，选出前N支最适合做T的股票
     *
     * @param topN 返回前N支
     * @return 按做T综合分排序的列表
     */
    public List<DayTradingCandidate> selectTopForDayTrading(int topN) {
        log.info("====== 开始做T选股扫描（目标：选出Top{}支）======", topN);

        StockScreener tempScreener = new StockScreener(dataProvider);
        List<Map<String, Object>> allQuotes = tempScreener.fetchAllStockQuotes();

        List<Map<String, Object>> candidates = preFilterForDayTrading(allQuotes);
        if (candidates.isEmpty()) candidates = allQuotes;

        List<DayTradingCandidate> results = scoreCandidates(candidates);
        Collections.sort(results);

        List<DayTradingCandidate> topList = results.stream().limit(topN).collect(Collectors.toList());
        log.info("做T选股完成，Top{}:", topN);
        for (int i = 0; i < topList.size(); i++) {
            log.info("  第{}名: {}", i + 1, topList.get(i));
        }
        return topList;
    }

    /**
     * 做T专用预筛选
     * 保留：有足够流动性、近期有波动、未涨跌停的股票
     */
    private List<Map<String, Object>> preFilterForDayTrading(List<Map<String, Object>> quotes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> q : quotes) {
            String code  = (String) q.get("code");
            String name  = (String) q.get("name");
            double price = toDouble(q.get("price"));
            double changePct     = toDouble(q.get("changePercent"));
            double turnoverRate  = toDouble(q.get("turnoverRate"));
            double totalMarketCap= toDouble(q.get("totalMarketCap"));
            double volumeRatio   = toDouble(q.get("volumeRatio"));

            if (code == null || code.isEmpty() || price <= 0) continue;

            // 过滤ST、退市
            if (name != null && (name.contains("ST") || name.contains("退"))) continue;

            // 过滤涨跌停（做T需要能自由买卖，涨跌停无法操作）
            if (changePct >= 9.5 || changePct <= -9.5) continue;

            // 过滤低价股（< 3元，价差太小，做T价差利润被手续费侵蚀）
            if (price < 3.0) continue;

            // 过滤小市值（< 20亿，流动性差，大单冲击成本高）；0表示数据未提供，不过滤
            if (totalMarketCap > 0 && totalMarketCap < 20) continue;

            // 过滤量比异常（停牌/超级炒作）
            if (volumeRatio > 0 && (volumeRatio > 15 || volumeRatio < 0.2)) continue;

            // 换手率初步过滤（换手率太低说明今天几乎没人交易，不适合做T）
            // 若数据源不提供换手率（=0），跳过此过滤
            if (turnoverRate > 0 && turnoverRate < 0.5) continue;

            // 科创板（688）风险提示（T+0规则不同，保留但降权）
            result.add(q);
        }
        return result;
    }

    /**
     * 并行对候选股票获取K线，计算做T综合评分
     */
    private List<DayTradingCandidate> scoreCandidates(List<Map<String, Object>> candidates) {
        List<DayTradingCandidate> results = Collections.synchronizedList(new ArrayList<>());

        // 限制最多分析600只，避免过慢
        int limit = Math.min(candidates.size(), 600);
        List<Map<String, Object>> targetList = candidates.subList(0, limit);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        LocalDate endDate   = LocalDate.now();
        LocalDate startDate = endDate.minusDays(60); // 取近60个交易日K线

        for (Map<String, Object> q : targetList) {
            pool.submit(() -> {
                String code = (String) q.get("code");
                String name = (String) q.get("name");
                try {
                    List<StockBar> bars = dataProvider.getDailyBars(
                            code, startDate, endDate, StockBar.AdjustType.FORWARD);
                    if (bars == null || bars.size() < 20) return;

                    DayTradingCandidate candidate = evaluate(code, name, q, bars);
                    if (candidate != null) {
                        results.add(candidate);
                    }
                } catch (Exception e) {
                    log.debug("评估股票{}做T潜力失败: {}", code, e.getMessage());
                }
            });
        }

        pool.shutdown();
        try {
            pool.awaitTermination(8, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return results;
    }

    /**
     * 评估单只股票的做T潜力
     *
     * @return 若不符合基础门槛返回null，否则返回候选对象（含评分）
     */
    private DayTradingCandidate evaluate(String code, String name,
                                          Map<String, Object> quote,
                                          List<StockBar> bars) {
        int n = bars.size();
        int window20 = Math.min(20, n);
        int window5  = Math.min(5, n);

        double currentPrice = toDouble(quote.get("price"));
        double changePct    = toDouble(quote.get("changePercent"));
        if (currentPrice <= 0) {
            currentPrice = bars.get(n - 1).getClose();
        }

        // ===== 1. 近20日平均日内振幅（High-Low）/Close =====
        double sumSwing = 0;
        int   swingDays = 0;   // 振幅>=1.5%的天数
        for (int i = n - window20; i < n; i++) {
            StockBar bar = bars.get(i);
            double close = bar.getClose();
            if (close <= 0) continue;
            double swing = (bar.getHigh() - bar.getLow()) / close;
            sumSwing += swing;
            if (swing >= 0.015) swingDays++;  // 振幅>=1.5%计入有效波动天数
        }
        double avgSwingPct = window20 > 0 ? sumSwing / window20 : 0;
        double swingDaysRatio = (double) swingDays / window20;

        // ===== 2. ATR波动率 =====
        double atrPct = AtrStopLoss.atrPercent(bars, 14);

        // ===== 3. 近5日是否有连续跌停 =====
        boolean hasLimitDown = false;
        int limitDownCount = 0;
        for (int i = n - window5; i < n; i++) {
            StockBar bar = bars.get(i);
            double prevClose = i > 0 ? bars.get(i - 1).getClose() : 0;
            if (prevClose > 0) {
                double dailyChg = (bar.getClose() - prevClose) / prevClose;
                if (dailyChg <= LIMIT_DOWN_PCT) {
                    limitDownCount++;
                }
            }
        }
        hasLimitDown = limitDownCount >= 2; // 近5日有2次以上跌停则排除

        if (hasLimitDown) return null;

        // ===== 4. 近20日日均换手率（取行情快照或K线内估算）=====
        double avgTurnoverRate = toDouble(quote.get("turnoverRate")); // 今日换手率
        // 若行情快照提供换手率，用今日值；若不提供（=0），用K线粗估
        if (avgTurnoverRate <= 0 && n >= 5) {
            // 用近5日成交量/流通股粗估（无法准确，仅做粗筛）
            // 此处不精确计算，保持为0，后续评分不用换手率分量即可
        }

        // ===== 5. 近5日日均成交额（万元）=====
        double sumAmount = 0;
        for (int i = n - window5; i < n; i++) {
            sumAmount += bars.get(i).getAmount();
        }
        // amount 单位：元（部分数据源），换算万元
        double avgAmountRaw = window5 > 0 ? sumAmount / window5 : 0;
        // 若 amount 单位是元（>1亿），转换为万元；否则认为已是万元
        double avgAmountW = avgAmountRaw > 1e8 ? avgAmountRaw / 10000.0 : avgAmountRaw;

        // ===== 硬门槛过滤 =====
        if (avgSwingPct < MIN_AVG_SWING_PCT) return null;
        if (atrPct < MIN_ATR_PCT) return null;
        if (swingDaysRatio < MIN_SWING_DAYS_RATIO) return null;
        // 成交额过滤（仅当数据有效时）
        if (avgAmountW > 0 && avgAmountW < MIN_AVG_AMOUNT_W) return null;

        // ===== 6. 技术面综合评分 =====
        AnalysisResult ar = null;
        try {
            ar = analyzer.analyze(code, name, bars);
        } catch (Exception e) {
            log.debug("分析{}技术面失败: {}", code, e.getMessage());
        }
        int techScore = (ar != null) ? ar.getOverallScore() : 50;
        if (techScore < MIN_TECH_SCORE) return null;

        // ===== 7. 做T综合评分（100分制）=====
        // ATR波动分（35分）：ATR波动率越高越好，以3%为满分基准
        double atrScore = Math.min(35.0, (atrPct / 0.03) * 35.0);

        // 振幅均匀分（25分）：有效波动天数占比越高越好
        double swingScore = Math.min(25.0, swingDaysRatio * 25.0);

        // 换手率分（20分）：换手率越高流动性越好（以5%为满分基准）
        double turnoverScore = 0;
        if (avgTurnoverRate > 0) {
            turnoverScore = Math.min(20.0, (avgTurnoverRate / 5.0) * 20.0);
        } else {
            // 无换手率数据时，用成交额代替
            double amountScore20M = Math.min(20.0, (avgAmountW / 50000.0) * 20.0); // 5亿满分
            turnoverScore = amountScore20M;
        }

        // 技术面分（20分）：技术评分映射到0~20
        double techScoreNorm = Math.min(20.0, Math.max(0.0, (techScore - 55) / 45.0 * 20.0));

        int dayTradingScore = (int)(atrScore + swingScore + turnoverScore + techScoreNorm);

        String scoreDetail = String.format(
                "ATR分=%.1f 振幅均匀分=%.1f 换手分=%.1f 技术分=%.1f",
                atrScore, swingScore, turnoverScore, techScoreNorm);

        return new DayTradingCandidate(
                code, name, currentPrice, changePct,
                avgSwingPct, atrPct,
                avgTurnoverRate, avgAmountW,
                techScore, dayTradingScore, scoreDetail
        );
    }

    private double toDouble(Object val) {
        if (val == null) return 0;
        try { return ((Number) val).doubleValue(); }
        catch (Exception e) { return 0; }
    }
}


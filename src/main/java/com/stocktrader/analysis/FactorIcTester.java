package com.stocktrader.analysis;

import com.stocktrader.model.FundamentalFactor;
import com.stocktrader.model.StockBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 因子有效性检验：IC 信息系数（Information Coefficient）
 * <p>
 * IC = 因子值与未来N日收益率的 Spearman 秩相关系数
 * <p>
 * 判断标准（行业经验）：
 *   |IC均值| > 0.02   因子有一定预测能力
 *   |IC均值| > 0.05   因子较强
 *   ICIR = IC均值/IC标准差 > 0.5 因子稳定性较好
 * <p>
 * 支持两类因子检验：
 * <ul>
 *   <li>基本面因子（PE/PB/ROE/净利增速等）：通过 buildSnapshots + batchTest</li>
 *   <li>技术面因子（MACD/RSI/KDJ/布林带等）：通过 buildTechSnapshots + batchTechTest</li>
 * </ul>
 * <p>
 * 使用流程（技术因子）：
 * <pre>
 *   FactorIcTester tester = new FactorIcTester();
 *   Map&lt;String, IcResult&gt; results = tester.batchTechTest(stockBarsMap, 5,
 *       LocalDate.of(2023,1,1), LocalDate.of(2025,1,1));
 *   results.values().forEach(IcResult::print);
 * </pre>
 */
public class FactorIcTester {

    private static final Logger log = LoggerFactory.getLogger(FactorIcTester.class);

    // ============================= 数据结构 =============================

    /**
     * 某个截面日期上，所有股票的（因子值，未来收益率）快照
     */
    public static class FactorSnapshot {
        public final LocalDate date;
        public final String stockCode;
        public final double factorValue;    // 当日因子值
        public double forwardReturn;        // 未来N日收益率（事后填充）

        public FactorSnapshot(LocalDate date, String stockCode, double factorValue) {
            this.date = date;
            this.stockCode = stockCode;
            this.factorValue = factorValue;
            this.forwardReturn = Double.NaN;
        }
    }

    /**
     * 单期（某个截面日期）的 IC 值
     */
    public static class PeriodIc {
        public final LocalDate date;
        public final double ic;            // Spearman IC
        public final int stockCount;       // 参与计算的股票数量

        public PeriodIc(LocalDate date, double ic, int stockCount) {
            this.date = date;
            this.ic = ic;
            this.stockCount = stockCount;
        }
    }

    /**
     * IC 检验结果汇总
     */
    public static class IcResult {
        public final String factorName;
        public final int holdingDays;     // 持有期（天）
        public final List<PeriodIc> icSeries;

        public final double icMean;       // IC均值（核心指标）
        public final double icStd;        // IC标准差
        public final double icir;         // ICIR = IC均值/IC标准差
        public final double positiveRate; // IC>0的期数占比（%）
        public final double absIcMean;    // |IC|均值

        public IcResult(String factorName, int holdingDays, List<PeriodIc> icSeries) {
            this.factorName = factorName;
            this.holdingDays = holdingDays;
            this.icSeries = icSeries;

            double[] ics = icSeries.stream().filter(p -> !Double.isNaN(p.ic))
                    .mapToDouble(p -> p.ic).toArray();
            this.icMean = Arrays.stream(ics).average().orElse(0);
            double variance = ics.length > 1
                    ? Arrays.stream(ics).map(v -> (v - icMean) * (v - icMean)).average().orElse(0) : 0;
            this.icStd = Math.sqrt(variance);
            this.icir  = icStd > 0 ? icMean / icStd : 0;
            this.positiveRate = ics.length > 0
                    ? Arrays.stream(ics).filter(v -> v > 0).count() * 100.0 / ics.length : 0;
            this.absIcMean = Arrays.stream(ics).map(Math::abs).average().orElse(0);
        }

        /** 因子有效性判断 */
        public String effectiveness() {
            if (Math.abs(icMean) >= 0.05 && Math.abs(icir) >= 0.5) return "★★★ 强有效因子";
            if (Math.abs(icMean) >= 0.03 && Math.abs(icir) >= 0.3) return "★★  中等因子";
            if (Math.abs(icMean) >= 0.02)                           return "★   弱有效因子";
            return "✗   无效因子（|IC|<0.02）";
        }

        /** 因子方向（正向/反向）*/
        public String direction() {
            return icMean > 0 ? "正向（因子值↑ → 未来收益↑）" : "反向（因子值↑ → 未来收益↓）";
        }

        public void print() {
            String sep  = "================================================================";
            String sepm = "----------------------------------------------------------------";
            System.out.println("\n" + sep);
            System.out.printf("【因子IC检验报告】因子：%s  持有期：%d日%n", factorName, holdingDays);
            System.out.println(sepm);
            System.out.printf("IC均值：       %+.4f%n", icMean);
            System.out.printf("|IC|均值：     %.4f%n",  absIcMean);
            System.out.printf("IC标准差：     %.4f%n",  icStd);
            System.out.printf("ICIR：         %+.4f%n", icir);
            System.out.printf("IC>0期数占比：  %.1f%%%n", positiveRate);
            System.out.printf("检验期数：     %d期%n",   icSeries.size());
            System.out.println(sepm);
            System.out.printf("有效性：   %s%n", effectiveness());
            System.out.printf("因子方向： %s%n", direction());
            System.out.println(sep);
        }
    }

    // ============================= 主要方法 =============================

    /**
     * 构建因子截面快照
     * @param stockBarsMap   各股票全量K线 (code -> bars 升序)
     * @param fundamentalMap 各股票基本面因子 (code -> fundamental)
     * @param factorExtractor 因子提取函数（从FundamentalFactor取因子值）
     * @param startDate      开始日期
     * @param endDate        结束日期
     * @return 所有截面的因子快照列表
     */
    public List<FactorSnapshot> buildSnapshots(
            Map<String, List<StockBar>> stockBarsMap,
            Map<String, FundamentalFactor> fundamentalMap,
            Function<FundamentalFactor, Double> factorExtractor,
            LocalDate startDate, LocalDate endDate) {

        List<FactorSnapshot> snapshots = new ArrayList<>();

        // 取所有交易日
        Set<LocalDate> allDates = new TreeSet<>();
        stockBarsMap.values().forEach(bars -> bars.forEach(b -> {
            if (b.getDate() != null && !b.getDate().isBefore(startDate)
                    && !b.getDate().isAfter(endDate)) {
                allDates.add(b.getDate());
            }
        }));

        // 每月第一个交易日取截面（月度IC）
        List<LocalDate> sampleDates = new ArrayList<>();
        int lastMonth = -1;
        for (LocalDate d : allDates) {
            if (d.getMonthValue() != lastMonth) {
                sampleDates.add(d);
                lastMonth = d.getMonthValue();
            }
        }

        for (LocalDate sampleDate : sampleDates) {
            for (Map.Entry<String, FundamentalFactor> entry : fundamentalMap.entrySet()) {
                String code = entry.getKey();
                FundamentalFactor factor = entry.getValue();
                if (factor == null) continue;

                double factorVal;
                try {
                    factorVal = factorExtractor.apply(factor);
                } catch (Exception e) {
                    continue;
                }
                if (Double.isNaN(factorVal) || factorVal == 0) continue;

                snapshots.add(new FactorSnapshot(sampleDate, code, factorVal));
            }
        }

        log.info("构建因子截面快照：{} 个记录", snapshots.size());
        return snapshots;
    }

    /**
     * 填充未来N日收益率（事后填充）
     * @param snapshots   因子截面快照列表
     * @param stockBarsMap 各股票K线
     * @param holdingDays  持有期（交易日）
     */
    public void fillForwardReturns(List<FactorSnapshot> snapshots,
                                    Map<String, List<StockBar>> stockBarsMap,
                                    int holdingDays) {
        // 将K线按code分组，日期 -> 收盘价映射
        Map<String, Map<LocalDate, Double>> priceMaps = new HashMap<>();
        for (Map.Entry<String, List<StockBar>> entry : stockBarsMap.entrySet()) {
            Map<LocalDate, Double> priceMap = new LinkedHashMap<>();
            for (StockBar bar : entry.getValue()) {
                if (bar.getDate() != null) priceMap.put(bar.getDate(), bar.getClose());
            }
            priceMaps.put(entry.getKey(), priceMap);
        }

        for (FactorSnapshot snap : snapshots) {
            Map<LocalDate, Double> priceMap = priceMaps.get(snap.stockCode);
            if (priceMap == null) continue;

            Double entryPrice = priceMap.get(snap.date);
            if (entryPrice == null || entryPrice <= 0) continue;

            // 找N个交易日后的价格
            List<LocalDate> sortedDates = priceMap.keySet().stream()
                    .filter(d -> d.isAfter(snap.date))
                    .sorted()
                    .collect(Collectors.toList());

            if (sortedDates.size() >= holdingDays) {
                LocalDate exitDate = sortedDates.get(holdingDays - 1);
                Double exitPrice   = priceMap.get(exitDate);
                if (exitPrice != null && exitPrice > 0) {
                    snap.forwardReturn = (exitPrice - entryPrice) / entryPrice;
                }
            }
        }
        log.info("填充未来{}日收益率完成", holdingDays);
    }

    /**
     * 计算 IC 时间序列（Spearman 秩相关）
     * @param snapshots   已填充 forwardReturn 的快照
     * @param minStocks   每期最少股票数（不足则跳过）
     * @return IC 检验结果
     */
    public IcResult calcIcSeries(List<FactorSnapshot> snapshots, int holdingDays,
                                  String factorName, int minStocks) {
        // 按截面日期分组
        Map<LocalDate, List<FactorSnapshot>> byDate = snapshots.stream()
                .filter(s -> !Double.isNaN(s.forwardReturn))
                .collect(Collectors.groupingBy(s -> s.date));

        List<PeriodIc> icSeries = new ArrayList<>();
        List<LocalDate> sortedDates = new ArrayList<>(byDate.keySet());
        Collections.sort(sortedDates);

        for (LocalDate date : sortedDates) {
            List<FactorSnapshot> period = byDate.get(date);
            if (period == null || period.size() < minStocks) continue;

            double ic = spearmanCorrelation(
                    period.stream().mapToDouble(s -> s.factorValue).toArray(),
                    period.stream().mapToDouble(s -> s.forwardReturn).toArray()
            );
            icSeries.add(new PeriodIc(date, ic, period.size()));
            log.debug("[{}] IC={} N={}", date, String.format("%.4f", ic), period.size());
        }

        log.info("IC检验完成：因子={} 持有期={}日 有效期数={}",
                factorName, holdingDays, icSeries.size());

        return new IcResult(factorName, holdingDays, icSeries);
    }

    /**
     * 多因子 IC 批量检验（一次检验多个因子）
     * @param stockBarsMap   K线数据
     * @param fundamentalMap 基本面数据
     * @param holdingDays    持有期
     * @param startDate      开始日期
     * @param endDate        结束日期
     * @return 因子名 -> IC结果
     */
    public Map<String, IcResult> batchTest(
            Map<String, List<StockBar>> stockBarsMap,
            Map<String, FundamentalFactor> fundamentalMap,
            int holdingDays, LocalDate startDate, LocalDate endDate) {

        // 定义待检验的因子
        Map<String, Function<FundamentalFactor, Double>> factors = new LinkedHashMap<>();
        factors.put("PE_TTM",       f -> f.getPeTtm() > 0 ? f.getPeTtm() : Double.NaN);
        factors.put("PB",           f -> f.getPb() > 0 ? f.getPb() : Double.NaN);
        factors.put("PS_TTM",       f -> f.getPsTtm() > 0 ? f.getPsTtm() : Double.NaN);
        factors.put("ROE",          f -> f.getRoe() != 0 ? f.getRoe() : Double.NaN);
        factors.put("ROA",          f -> f.getRoa() != 0 ? f.getRoa() : Double.NaN);
        factors.put("毛利率",        f -> f.getGrossMargin() != 0 ? f.getGrossMargin() : Double.NaN);
        factors.put("营收增速",       f -> f.getRevenueYoy() != 0 ? f.getRevenueYoy() : Double.NaN);
        factors.put("净利增速",       f -> f.getProfitYoy() != 0 ? f.getProfitYoy() : Double.NaN);
        factors.put("股息率",         f -> f.getDvRatio() >= 0 ? f.getDvRatio() : Double.NaN);
        factors.put("负债率",         f -> f.getDebtRatio() != 0 ? f.getDebtRatio() : Double.NaN);
        factors.put("基本面评分",      f -> (double) f.getFundamentalScore());
        factors.put("总市值(万元)",    f -> f.getTotalMv() > 0 ? f.getTotalMv() : Double.NaN);

        Map<String, IcResult> results = new LinkedHashMap<>();

        for (Map.Entry<String, Function<FundamentalFactor, Double>> entry : factors.entrySet()) {
            String factorName = entry.getKey();
            try {
                List<FactorSnapshot> snapshots = buildSnapshots(
                        stockBarsMap, fundamentalMap, entry.getValue(), startDate, endDate);
                fillForwardReturns(snapshots, stockBarsMap, holdingDays);
                IcResult icResult = calcIcSeries(snapshots, holdingDays, factorName, 5);
                results.put(factorName, icResult);
            } catch (Exception e) {
                log.warn("因子 {} IC检验失败: {}", factorName, e.getMessage());
            }
        }

        // 打印汇总
        printBatchSummary(results);
        return results;
    }

    /** 打印多因子IC汇总表 */
    public void printBatchSummary(Map<String, IcResult> results) {
        String sep  = "================================================================";
        String sepm = "----------------------------------------------------------------";
        System.out.println("\n" + sep);
        System.out.println("【多因子IC检验汇总】（按|IC均值|降序）");
        System.out.printf("%-14s %7s %7s %7s %7s %7s  %s%n",
                "因子", "IC均值", "ICIR", "|IC|均值", "IC>0%", "期数", "有效性");
        System.out.println(sepm);

        results.values().stream()
                .sorted(Comparator.comparingDouble((IcResult r) -> Math.abs(r.icMean)).reversed())
                .forEach(r -> System.out.printf("%-14s %+6.4f %+6.4f  %6.4f %5.1f%% %4d期  %s%n",
                        r.factorName, r.icMean, r.icir, r.absIcMean,
                        r.positiveRate, r.icSeries.size(), r.effectiveness().substring(0, 2)));
        System.out.println(sep);
    }

    // ============================= 技术因子截面快照 =============================

    /**
     * 技术因子提取函数接口（从某个截面的 K 线子列表中计算因子值）
     */
    @FunctionalInterface
    public interface TechFactorExtractor {
        /**
         * @param bars 截面当日及之前的全部 K 线（升序，最后一根为截面当日）
         * @return 因子值，若数据不足返回 Double.NaN
         */
        double extract(List<StockBar> bars);
    }

    /**
     * 构建技术因子截面快照（滚动窗口方式）
     * <p>
     * 每月第一个交易日为截面日期，取该日及之前所有 K 线计算技术指标值。
     *
     * @param stockBarsMap 各股票全量 K 线（code -> bars 升序）
     * @param extractor    技术因子提取函数
     * @param startDate    开始日期
     * @param endDate      结束日期
     * @return 因子截面快照列表（forwardReturn 尚未填充）
     */
    public List<FactorSnapshot> buildTechSnapshots(
            Map<String, List<StockBar>> stockBarsMap,
            TechFactorExtractor extractor,
            LocalDate startDate, LocalDate endDate) {

        List<FactorSnapshot> snapshots = new ArrayList<>();

        // 收集所有交易日（跨股票并集）
        Set<LocalDate> allDates = new TreeSet<>();
        for (List<StockBar> bars : stockBarsMap.values()) {
            for (StockBar bar : bars) {
                LocalDate d = bar.getDate();
                if (d != null && !d.isBefore(startDate) && !d.isAfter(endDate)) {
                    allDates.add(d);
                }
            }
        }

        // 每月首个交易日为截面
        List<LocalDate> sampleDates = new ArrayList<>();
        int lastMonth = -1;
        for (LocalDate d : allDates) {
            if (d.getMonthValue() != lastMonth) {
                sampleDates.add(d);
                lastMonth = d.getMonthValue();
            }
        }

        for (Map.Entry<String, List<StockBar>> entry : stockBarsMap.entrySet()) {
            String code = entry.getKey();
            List<StockBar> allBars = entry.getValue();  // 全量升序
            if (allBars == null || allBars.size() < 30) continue;

            // 构建日期索引，方便二分查找
            List<LocalDate> barDates = new ArrayList<>();
            for (StockBar b : allBars) barDates.add(b.getDate());

            for (LocalDate sampleDate : sampleDates) {
                // 找到截面日当天的索引（含截面日）
                int idx = upperBound(barDates, sampleDate);
                if (idx < 30) continue;  // 数据不足，跳过

                List<StockBar> window = allBars.subList(0, idx); // [0, idx) 截面日及之前

                double factorVal;
                try {
                    factorVal = extractor.extract(window);
                } catch (Exception e) {
                    continue;
                }
                if (Double.isNaN(factorVal)) continue;

                snapshots.add(new FactorSnapshot(sampleDate, code, factorVal));
            }
        }

        log.info("构建技术因子截面快照：{} 个记录", snapshots.size());
        return snapshots;
    }

    /**
     * 批量技术因子 IC 检验（一次检验所有内置技术因子）
     * <p>
     * 内置检验的技术因子：
     * <ul>
     *   <li>MACD_DIF、MACD_BAR（MACD相关）</li>
     *   <li>RSI6、RSI12（相对强弱）</li>
     *   <li>KDJ_K、KDJ_J（随机指标）</li>
     *   <li>BOLL_POS（布林带位置 0-1）</li>
     *   <li>BOLL_WIDTH（布林带带宽 %）</li>
     *   <li>VOL_RATIO（量比）</li>
     *   <li>MOMENTUM_5、MOMENTUM_20（动量：N日收益率）</li>
     *   <li>MA_SCORE（均线多头得分：价格相对多条均线的位置）</li>
     *   <li>OBV_SLOPE（OBV斜率：近5期OBV涨幅，衡量资金流向）</li>
     * </ul>
     *
     * @param stockBarsMap K 线数据
     * @param holdingDays  持有期（交易日）
     * @param startDate    检验开始日期
     * @param endDate      检验结束日期
     * @return 因子名 -> IC结果（按 |IC均值| 降序排列）
     */
    public Map<String, IcResult> batchTechTest(
            Map<String, List<StockBar>> stockBarsMap,
            int holdingDays, LocalDate startDate, LocalDate endDate) {

        // 定义技术因子提取器
        Map<String, TechFactorExtractor> factors = new LinkedHashMap<>();

        // ---- MACD ----
        factors.put("MACD_DIF", bars -> {
            double[] closes = extractClosesArr(bars);
            if (closes.length < 35) return Double.NaN;
            TechnicalIndicator.MACDResult m = TechnicalIndicator.macd(closes);
            return m.latestDif();
        });
        factors.put("MACD_BAR", bars -> {
            double[] closes = extractClosesArr(bars);
            if (closes.length < 35) return Double.NaN;
            TechnicalIndicator.MACDResult m = TechnicalIndicator.macd(closes);
            return m.latestMacd();
        });

        // ---- RSI ----
        factors.put("RSI6", bars -> {
            double[] closes = extractClosesArr(bars);
            if (closes.length < 8) return Double.NaN;
            return TechnicalIndicator.rsi(closes, 6);
        });
        factors.put("RSI12", bars -> {
            double[] closes = extractClosesArr(bars);
            if (closes.length < 14) return Double.NaN;
            return TechnicalIndicator.rsi(closes, 12);
        });

        // ---- KDJ ----
        factors.put("KDJ_K", bars -> {
            if (bars.size() < 10) return Double.NaN;
            TechnicalIndicator.KDJResult k = TechnicalIndicator.kdj(bars);
            return k.latestK();
        });
        factors.put("KDJ_J", bars -> {
            if (bars.size() < 10) return Double.NaN;
            TechnicalIndicator.KDJResult k = TechnicalIndicator.kdj(bars);
            return k.latestJ();
        });

        // ---- 布林带位置（0=下轨, 1=上轨, >1=突破上轨, <0=突破下轨）----
        factors.put("BOLL_POS", bars -> {
            double[] closes = extractClosesArr(bars);
            if (closes.length < 22) return Double.NaN;
            TechnicalIndicator.BollingerResult b = TechnicalIndicator.bollinger(closes);
            double upper = b.latestUpper();
            double lower = b.latestLower();
            if (Double.isNaN(upper) || Double.isNaN(lower)) return Double.NaN;
            double width = upper - lower;
            if (width <= 0) return Double.NaN;
            return (closes[closes.length - 1] - lower) / width;
        });

        // ---- 布林带带宽（窄带预示突破；宽带预示回归）----
        factors.put("BOLL_WIDTH", bars -> {
            double[] closes = extractClosesArr(bars);
            if (closes.length < 22) return Double.NaN;
            TechnicalIndicator.BollingerResult b = TechnicalIndicator.bollinger(closes);
            return b.getBandWidth();
        });

        // ---- 量比（当日成交量/过去5日均量）----
        factors.put("VOL_RATIO", bars -> {
            if (bars.size() < 7) return Double.NaN;
            return TechnicalIndicator.volumeRatio(bars, 5);
        });

        // ---- 动量（5日收益率）：短线动量 ----
        factors.put("MOMENTUM_5", bars -> {
            double[] closes = extractClosesArr(bars);
            if (closes.length < 6) return Double.NaN;
            int n = closes.length;
            double ref = closes[n - 6];
            return ref > 0 ? (closes[n - 1] - ref) / ref : Double.NaN;
        });

        // ---- 动量（20日收益率）：中期动量 ----
        factors.put("MOMENTUM_20", bars -> {
            double[] closes = extractClosesArr(bars);
            if (closes.length < 21) return Double.NaN;
            int n = closes.length;
            double ref = closes[n - 21];
            return ref > 0 ? (closes[n - 1] - ref) / ref : Double.NaN;
        });

        // ---- 均线多头排列得分（0/25/50/75/100）----
        factors.put("MA_SCORE", bars -> {
            double[] closes = extractClosesArr(bars);
            if (closes.length < 62) return Double.NaN;
            double price = closes[closes.length - 1];
            double ma5  = TechnicalIndicator.lastValid(TechnicalIndicator.smaArray(closes, 5));
            double ma10 = TechnicalIndicator.lastValid(TechnicalIndicator.smaArray(closes, 10));
            double ma20 = TechnicalIndicator.lastValid(TechnicalIndicator.smaArray(closes, 20));
            double ma60 = TechnicalIndicator.lastValid(TechnicalIndicator.smaArray(closes, 60));
            if (Double.isNaN(ma5) || Double.isNaN(ma10)
                    || Double.isNaN(ma20) || Double.isNaN(ma60)) return Double.NaN;
            int cnt = 0;
            if (price > ma5)  cnt++;
            if (ma5 > ma10)   cnt++;
            if (ma10 > ma20)  cnt++;
            if (ma20 > ma60)  cnt++;
            return (double) cnt * 25; // 0 / 25 / 50 / 75 / 100
        });

        // ---- OBV 斜率（近5期 OBV 相对首期的涨幅）----
        factors.put("OBV_SLOPE", bars -> {
            if (bars.size() < 6) return Double.NaN;
            double[] obv = TechnicalIndicator.obvArray(bars);
            int n = obv.length;
            double base = obv[n - 6];
            return Math.abs(base) > 1e-8 ? (obv[n - 1] - base) / Math.abs(base) : Double.NaN;
        });

        // ---- ATR 百分比（波动率因子；高波动 → 未来收益方差大）----
        factors.put("ATR_PCT", bars -> {
            if (bars.size() < 15) return Double.NaN;
            double[] atr = TechnicalIndicator.atrArray(bars, 14);
            double lastAtr = TechnicalIndicator.lastValid(atr);
            double lastClose = bars.get(bars.size() - 1).getClose();
            return lastClose > 0 ? lastAtr / lastClose * 100 : Double.NaN;
        });

        Map<String, IcResult> results = new LinkedHashMap<>();
        for (Map.Entry<String, TechFactorExtractor> entry : factors.entrySet()) {
            String factorName = entry.getKey();
            try {
                List<FactorSnapshot> snapshots = buildTechSnapshots(
                        stockBarsMap, entry.getValue(), startDate, endDate);
                if (snapshots.size() < 20) {
                    log.warn("技术因子 {} 有效截面过少（{}个），跳过", factorName, snapshots.size());
                    continue;
                }
                fillForwardReturns(snapshots, stockBarsMap, holdingDays);
                IcResult icResult = calcIcSeries(snapshots, holdingDays, factorName, 5);
                results.put(factorName, icResult);
            } catch (Exception e) {
                log.warn("技术因子 {} IC检验失败: {}", factorName, e.getMessage());
            }
        }

        printBatchSummary(results);
        return results;
    }

    /**
     * 综合检验报告：技术因子 + 基本面因子一起输出，并给出权重建议
     *
     * @param techResults  技术因子IC结果
     * @param fundResults  基本面因子IC结果（可为 null）
     * @return 有效因子权重建议（因子名 -> 建议权重 0-1，总和归一化到 1.0）
     */
    public Map<String, Double> generateWeightSuggestion(
            Map<String, IcResult> techResults,
            Map<String, IcResult> fundResults) {

        String sep  = "================================================================";
        String sepm = "----------------------------------------------------------------";

        System.out.println("\n" + sep);
        System.out.println("【IC检验综合报告 & 权重建议】");
        System.out.println(sepm);

        // 合并所有结果，按 |IC均值| 降序
        Map<String, IcResult> all = new LinkedHashMap<>();
        if (techResults != null) all.putAll(techResults);
        if (fundResults != null) all.putAll(fundResults);

        List<Map.Entry<String, IcResult>> sorted = new ArrayList<>(all.entrySet());
        sorted.sort((a, b) -> Double.compare(
                Math.abs(b.getValue().icMean), Math.abs(a.getValue().icMean)));

        System.out.println("\n▶ 有效因子（建议保留）：");
        System.out.printf("  %-16s %7s %7s %7s  %s%n",
                "因子", "IC均值", "ICIR", "|IC|均值", "有效性");
        System.out.println("  " + sepm);

        // 仅以有效因子计算原始权重（基于 |IC均值| 占比）
        Map<String, Double> rawWeights = new LinkedHashMap<>();
        for (Map.Entry<String, IcResult> e : sorted) {
            IcResult r = e.getValue();
            if (Math.abs(r.icMean) >= 0.02) {
                rawWeights.put(e.getKey(), Math.abs(r.icMean));
                System.out.printf("  %-16s %+6.4f %+6.4f  %6.4f  %s%n",
                        e.getKey(), r.icMean, r.icir, r.absIcMean, r.effectiveness());
            }
        }

        System.out.println("\n▶ 无效因子（建议淘汰/降权）：");
        boolean hasInvalid = false;
        for (Map.Entry<String, IcResult> e : sorted) {
            if (Math.abs(e.getValue().icMean) < 0.02) {
                System.out.printf("  ✗ %-14s  |IC|均值=%.4f  →  建议权重置 0%n",
                        e.getKey(), e.getValue().absIcMean);
                hasInvalid = true;
            }
        }
        if (!hasInvalid) System.out.println("  （无）");

        // 归一化权重
        double total = rawWeights.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Double> normalizedWeights = new LinkedHashMap<>();
        if (total > 0) {
            rawWeights.forEach((k, v) -> normalizedWeights.put(k, v / total));
        }

        System.out.println("\n▶ IC 权重建议（归一化）：");
        normalizedWeights.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> System.out.printf("  %-16s  %.4f  (%.1f%%)%n",
                        e.getKey(), e.getValue(), e.getValue() * 100));
        System.out.println(sep);

        return normalizedWeights;
    }

    // ========== 内部工具方法 ==========

    /**
     * 从 StockBar 列表提取 close 数组（支持 getDate() == null 的 bar）
     */
    private static double[] extractClosesArr(List<StockBar> bars) {
        double[] arr = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) arr[i] = bars.get(i).getClose();
        return arr;
    }

    /**
     * 二分查找：返回 dates 列表中第一个 > target 的位置（即 <= target 的个数）
     * 等价于 C++ 的 upper_bound
     */
    private static int upperBound(List<LocalDate> dates, LocalDate target) {
        int lo = 0, hi = dates.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (!dates.get(mid).isAfter(target)) lo = mid + 1;
            else hi = mid;
        }
        return lo; // 即满足 date <= target 的元素数量
    }

    // ============================= Spearman 秩相关 =============================

    /**
     * 计算 Spearman 秩相关系数
     * Spearman IC = Pearson(rank(X), rank(Y))
     */
    public static double spearmanCorrelation(double[] x, double[] y) {
        if (x.length != y.length || x.length < 2) return Double.NaN;
        int n = x.length;
        double[] rankX = rank(x);
        double[] rankY = rank(y);
        return pearsonCorrelation(rankX, rankY, n);
    }

    private static double pearsonCorrelation(double[] rx, double[] ry, int n) {
        double meanX = 0, meanY = 0;
        for (int i = 0; i < n; i++) { meanX += rx[i]; meanY += ry[i]; }
        meanX /= n; meanY /= n;

        double cov = 0, varX = 0, varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = rx[i] - meanX, dy = ry[i] - meanY;
            cov  += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        double denom = Math.sqrt(varX * varY);
        return denom > 0 ? cov / denom : 0;
    }

    /** 计算秩次（有并列时用平均秩）*/
    private static double[] rank(double[] arr) {
        int n = arr.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, Comparator.comparingDouble(i -> arr[i]));

        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            // 找相同值的范围
            while (j + 1 < n && arr[idx[j + 1]] == arr[idx[i]]) j++;
            double avgRank = (i + j + 2.0) / 2; // 1-indexed
            for (int k = i; k <= j; k++) ranks[idx[k]] = avgRank;
            i = j + 1;
        }
        return ranks;
    }
}


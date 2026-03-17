package com.stocktrader.analysis;

import com.stocktrader.model.StockBar;

import java.util.List;

/**
 * 技术指标计算工具类
 * 包含：MA、EMA、MACD、RSI、KDJ、布林带、ATR、OBV等
 */
public class TechnicalIndicator {

    private TechnicalIndicator() {}

    // ============================= 移动平均线 MA =============================

    /**
     * 计算简单移动平均线 SMA
     * @param closes 收盘价列表（时间升序）
     * @param period 周期
     * @return 最新一根MA值
     */
    public static double sma(List<Double> closes, int period) {
        if (closes == null || closes.size() < period) return Double.NaN;
        int size = closes.size();
        double sum = 0;
        for (int i = size - period; i < size; i++) {
            sum += closes.get(i);
        }
        return sum / period;
    }

    /**
     * 计算SMA序列
     */
    public static double[] smaArray(double[] closes, int period) {
        int n = closes.length;
        double[] result = new double[n];
        for (int i = 0; i < n; i++) {
            if (i < period - 1) {
                result[i] = Double.NaN;
            } else {
                double sum = 0;
                for (int j = i - period + 1; j <= i; j++) {
                    sum += closes[j];
                }
                result[i] = sum / period;
            }
        }
        return result;
    }

    /**
     * 计算指数移动平均线 EMA
     * @param closes 收盘价数组
     * @param period 周期
     * @return EMA数组
     */
    public static double[] emaArray(double[] closes, int period) {
        int n = closes.length;
        double[] result = new double[n];
        double k = 2.0 / (period + 1);

        // 第一个EMA用SMA初始化
        double sum = 0;
        for (int i = 0; i < period && i < n; i++) {
            sum += closes[i];
        }
        result[period - 1] = sum / period;

        for (int i = period; i < n; i++) {
            result[i] = closes[i] * k + result[i - 1] * (1 - k);
        }

        // 前面的值设为NaN
        for (int i = 0; i < period - 1; i++) {
            result[i] = Double.NaN;
        }

        return result;
    }

    /**
     * 获取EMA最新值
     */
    public static double ema(double[] closes, int period) {
        double[] arr = emaArray(closes, period);
        return arr[arr.length - 1];
    }

    // ============================= MACD =============================

    /**
     * MACD指标结果
     */
    public static class MACDResult {
        public final double[] dif;   // DIF = EMA12 - EMA26
        public final double[] dea;   // DEA = EMA9(DIF)
        public final double[] macd;  // MACD柱 = 2 * (DIF - DEA)

        public MACDResult(double[] dif, double[] dea, double[] macd) {
            this.dif = dif;
            this.dea = dea;
            this.macd = macd;
        }

        /** 获取最新DIF */
        public double latestDif() { return dif[dif.length - 1]; }
        /** 获取最新DEA */
        public double latestDea() { return dea[dea.length - 1]; }
        /** 获取最新MACD柱 */
        public double latestMacd() { return macd[macd.length - 1]; }

        /** 是否金叉（DIF上穿DEA）*/
        public boolean isGoldenCross() {
            if (dif.length < 2) return false;
            return dif[dif.length - 2] < dea[dea.length - 2] &&
                   dif[dif.length - 1] > dea[dea.length - 1];
        }

        /** 是否死叉（DIF下穿DEA）*/
        public boolean isDeathCross() {
            if (dif.length < 2) return false;
            return dif[dif.length - 2] > dea[dea.length - 2] &&
                   dif[dif.length - 1] < dea[dea.length - 1];
        }
    }

    /**
     * 计算MACD指标
     * @param closes 收盘价数组
     * @param fastPeriod 快线周期（默认12）
     * @param slowPeriod 慢线周期（默认26）
     * @param signalPeriod 信号线周期（默认9）
     */
    public static MACDResult macd(double[] closes, int fastPeriod, int slowPeriod, int signalPeriod) {
        double[] ema12 = emaArray(closes, fastPeriod);
        double[] ema26 = emaArray(closes, slowPeriod);

        int n = closes.length;
        double[] dif = new double[n];
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(ema12[i]) || Double.isNaN(ema26[i])) {
                dif[i] = Double.NaN;
            } else {
                dif[i] = ema12[i] - ema26[i];
            }
        }

        // 对有效的DIF计算DEA（EMA9(DIF)）
        double[] dea = new double[n];
        double k = 2.0 / (signalPeriod + 1);
        boolean deaStarted = false;

        for (int i = 0; i < n; i++) {
            if (Double.isNaN(dif[i])) {
                dea[i] = Double.NaN;
                continue;
            }
            if (!deaStarted) {
                dea[i] = dif[i];
                deaStarted = true;
            } else {
                dea[i] = dif[i] * k + dea[i - 1] * (1 - k);
            }
        }

        double[] macdBar = new double[n];
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(dif[i]) || Double.isNaN(dea[i])) {
                macdBar[i] = Double.NaN;
            } else {
                macdBar[i] = 2 * (dif[i] - dea[i]);
            }
        }

        return new MACDResult(dif, dea, macdBar);
    }

    /** 使用默认参数计算MACD */
    public static MACDResult macd(double[] closes) {
        return macd(closes, 12, 26, 9);
    }

    // ============================= RSI =============================

    /**
     * 计算RSI相对强弱指标
     * @param closes 收盘价数组
     * @param period 周期（通常6、12、24）
     * @return RSI数组
     */
    public static double[] rsiArray(double[] closes, int period) {
        int n = closes.length;
        double[] rsi = new double[n];

        if (n < period + 1) {
            for (int i = 0; i < n; i++) rsi[i] = Double.NaN;
            return rsi;
        }

        double avgGain = 0;
        double avgLoss = 0;

        // 计算初始平均涨跌
        for (int i = 1; i <= period; i++) {
            double diff = closes[i] - closes[i - 1];
            if (diff > 0) avgGain += diff;
            else avgLoss += (-diff);
        }
        avgGain /= period;
        avgLoss /= period;

        // 第一个RSI
        for (int i = 0; i < period; i++) rsi[i] = Double.NaN;
        rsi[period] = avgLoss == 0 ? 100 : 100 - (100 / (1 + avgGain / avgLoss));

        // 后续RSI使用Wilder平滑法
        for (int i = period + 1; i < n; i++) {
            double diff = closes[i] - closes[i - 1];
            double gain = diff > 0 ? diff : 0;
            double loss = diff < 0 ? -diff : 0;

            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;

            rsi[i] = avgLoss == 0 ? 100 : 100 - (100 / (1 + avgGain / avgLoss));
        }

        return rsi;
    }

    /** 获取RSI最新值 */
    public static double rsi(double[] closes, int period) {
        double[] arr = rsiArray(closes, period);
        return arr[arr.length - 1];
    }

    // ============================= KDJ =============================

    /**
     * KDJ指标结果
     */
    public static class KDJResult {
        public final double[] k;
        public final double[] d;
        public final double[] j;

        public KDJResult(double[] k, double[] d, double[] j) {
            this.k = k;
            this.d = d;
            this.j = j;
        }

        public double latestK() { return k[k.length - 1]; }
        public double latestD() { return d[d.length - 1]; }
        public double latestJ() { return j[j.length - 1]; }

        /** 超卖区间（K<20 且 D<20）*/
        public boolean isOversold() {
            return latestK() < 20 && latestD() < 20;
        }

        /** 超买区间（K>80 且 D>80）*/
        public boolean isOverbought() {
            return latestK() > 80 && latestD() > 80;
        }

        /** KD金叉 */
        public boolean isGoldenCross() {
            if (k.length < 2) return false;
            return k[k.length - 2] < d[d.length - 2] &&
                   k[k.length - 1] > d[d.length - 1];
        }

        /** KD死叉 */
        public boolean isDeathCross() {
            if (k.length < 2) return false;
            return k[k.length - 2] > d[d.length - 2] &&
                   k[k.length - 1] < d[d.length - 1];
        }
    }

    /**
     * 计算KDJ随机指标
     * @param bars K线数据（含高低收）
     * @param nPeriod RSV计算周期（默认9）
     * @param mK K平滑系数（默认3）
     * @param mD D平滑系数（默认3）
     */
    public static KDJResult kdj(List<StockBar> bars, int nPeriod, int mK, int mD) {
        int n = bars.size();
        double[] k = new double[n];
        double[] d = new double[n];
        double[] j = new double[n];

        // 初始值50
        double prevK = 50;
        double prevD = 50;

        for (int i = 0; i < n; i++) {
            if (i < nPeriod - 1) {
                k[i] = Double.NaN;
                d[i] = Double.NaN;
                j[i] = Double.NaN;
                continue;
            }

            // 计算RSV（原始随机值）
            double highest = Double.MIN_VALUE;
            double lowest = Double.MAX_VALUE;
            for (int m = i - nPeriod + 1; m <= i; m++) {
                highest = Math.max(highest, bars.get(m).getHigh());
                lowest = Math.min(lowest, bars.get(m).getLow());
            }

            double close = bars.get(i).getClose();
            double rsv = highest == lowest ? 50 :
                    (close - lowest) / (highest - lowest) * 100;

            // K = EMA(RSV, mK)
            k[i] = (rsv + (mK - 1) * prevK) / mK;
            // D = EMA(K, mD)
            d[i] = (k[i] + (mD - 1) * prevD) / mD;
            // J = 3K - 2D
            j[i] = 3 * k[i] - 2 * d[i];

            prevK = k[i];
            prevD = d[i];
        }

        return new KDJResult(k, d, j);
    }

    /** 使用默认参数计算KDJ */
    public static KDJResult kdj(List<StockBar> bars) {
        return kdj(bars, 9, 3, 3);
    }

    // ============================= 布林带 Bollinger Bands =============================

    /**
     * 布林带结果
     */
    public static class BollingerResult {
        public final double[] upper;   // 上轨
        public final double[] middle;  // 中轨（MA20）
        public final double[] lower;   // 下轨

        public BollingerResult(double[] upper, double[] middle, double[] lower) {
            this.upper = upper;
            this.middle = middle;
            this.lower = lower;
        }

        public double latestUpper() { return upper[upper.length - 1]; }
        public double latestMiddle() { return middle[middle.length - 1]; }
        public double latestLower() { return lower[lower.length - 1]; }

        /** 价格是否突破上轨 */
        public boolean isBreakingUpper(double price) { return price > latestUpper(); }

        /** 价格是否跌破下轨 */
        public boolean isBreakingLower(double price) { return price < latestLower(); }

        /** 带宽（越窄越可能即将突破）*/
        public double getBandWidth() {
            double mid = latestMiddle();
            return mid > 0 ? (latestUpper() - latestLower()) / mid * 100 : 0;
        }
    }

    /**
     * 计算布林带
     * @param closes 收盘价数组
     * @param period 周期（默认20）
     * @param stdMultiplier 标准差倍数（默认2）
     */
    public static BollingerResult bollinger(double[] closes, int period, double stdMultiplier) {
        int n = closes.length;
        double[] upper = new double[n];
        double[] middle = smaArray(closes, period);
        double[] lower = new double[n];

        for (int i = 0; i < n; i++) {
            if (i < period - 1) {
                upper[i] = Double.NaN;
                lower[i] = Double.NaN;
                continue;
            }

            // 计算标准差
            double mean = middle[i];
            double sumSq = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double diff = closes[j] - mean;
                sumSq += diff * diff;
            }
            double std = Math.sqrt(sumSq / period);

            upper[i] = mean + stdMultiplier * std;
            lower[i] = mean - stdMultiplier * std;
        }

        return new BollingerResult(upper, middle, lower);
    }

    /** 使用默认参数计算布林带 */
    public static BollingerResult bollinger(double[] closes) {
        return bollinger(closes, 20, 2.0);
    }

    // ============================= ATR 平均真实波幅 =============================

    /**
     * 计算ATR（Average True Range）
     * @param bars K线数据
     * @param period 周期（默认14）
     * @return ATR数组
     */
    public static double[] atrArray(List<StockBar> bars, int period) {
        int n = bars.size();
        double[] tr = new double[n];
        double[] atr = new double[n];

        for (int i = 0; i < n; i++) {
            StockBar bar = bars.get(i);
            if (i == 0) {
                tr[i] = bar.getHigh() - bar.getLow();
            } else {
                double prevClose = bars.get(i - 1).getClose();
                tr[i] = Math.max(bar.getHigh() - bar.getLow(),
                        Math.max(Math.abs(bar.getHigh() - prevClose),
                                Math.abs(bar.getLow() - prevClose)));
            }
        }

        // Wilder平滑ATR
        if (n >= period) {
            double sum = 0;
            for (int i = 0; i < period; i++) sum += tr[i];
            atr[period - 1] = sum / period;
            for (int i = period; i < n; i++) {
                atr[i] = (atr[i - 1] * (period - 1) + tr[i]) / period;
            }
        }

        for (int i = 0; i < period - 1; i++) atr[i] = Double.NaN;
        return atr;
    }

    // ============================= 成交量指标 =============================

    /**
     * 计算量比（当日成交量/过去N日均量）
     */
    public static double volumeRatio(List<StockBar> bars, int period) {
        int n = bars.size();
        if (n < period + 1) return 1.0;

        double currentVolume = bars.get(n - 1).getVolume();
        double sumVolume = 0;
        for (int i = n - 1 - period; i < n - 1; i++) {
            sumVolume += bars.get(i).getVolume();
        }
        double avgVolume = sumVolume / period;
        return avgVolume > 0 ? currentVolume / avgVolume : 1.0;
    }

    /**
     * 计算OBV能量潮
     */
    public static double[] obvArray(List<StockBar> bars) {
        int n = bars.size();
        double[] obv = new double[n];
        if (n == 0) return obv;

        obv[0] = bars.get(0).getVolume();
        for (int i = 1; i < n; i++) {
            double prevClose = bars.get(i - 1).getClose();
            double currClose = bars.get(i).getClose();
            long vol = bars.get(i).getVolume();
            if (currClose > prevClose) {
                obv[i] = obv[i - 1] + vol;
            } else if (currClose < prevClose) {
                obv[i] = obv[i - 1] - vol;
            } else {
                obv[i] = obv[i - 1];
            }
        }
        return obv;
    }

    // ============================= 辅助工具 =============================

    /**
     * 将StockBar列表中的收盘价转换为double数组
     */
    public static double[] extractCloses(List<StockBar> bars) {
        double[] result = new double[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            result[i] = bars.get(i).getClose();
        }
        return result;
    }

    /**
     * 判断均线多头排列（MA5>MA10>MA20>MA60）
     */
    public static boolean isBullishAlignment(double ma5, double ma10, double ma20, double ma60) {
        return ma5 > ma10 && ma10 > ma20 && ma20 > ma60;
    }

    /**
     * 判断均线空头排列
     */
    public static boolean isBearishAlignment(double ma5, double ma10, double ma20, double ma60) {
        return ma5 < ma10 && ma10 < ma20 && ma20 < ma60;
    }

    /**
     * 安全获取数组最后一个有效值
     */
    public static double lastValid(double[] arr) {
        for (int i = arr.length - 1; i >= 0; i--) {
            if (!Double.isNaN(arr[i])) return arr[i];
        }
        return Double.NaN;
    }
}


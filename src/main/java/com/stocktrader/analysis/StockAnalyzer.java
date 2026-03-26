package com.stocktrader.analysis;

import com.stocktrader.model.AnalysisResult;
import com.stocktrader.model.StockBar;
import com.stocktrader.model.TradeSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 股票综合技术分析器
 * 整合多种技术指标，给出综合评分和操作建议。
 * <p>
 * 【权重依据】经过历史 IC 检验（FactorIcTester#batchTechTest），
 * 各技术因子对未来 5 日收益率的预测能力排序（A 股全市场月度截面，2023-2025）：
 * <pre>
 *  因子           |IC均值|  ICIR  有效性
 *  MA_SCORE        较强     ↑      ★★★  → 权重 0.25（调降：为新因子腾出权重）
 *  MOMENTUM_20     较强     ↑      ★★★  → 权重 0.18（中期动量）
 *  MACD_DIF        中等     ↑      ★★   → 权重 0.18
 *  MOMENTUM_5      中等     ↑      ★★   → 权重 0.11（短期动量，反向弱）
 *  OBV_SLOPE       中等     ↑      ★★   → 权重 0.10（[P3-2新增] OBV斜率：资金持续流入）
 *  RSI6            弱       ↓      ★    → 权重 0.09（超卖反弹弱信号）
 *  WEEK52_POSITION 中等     ↑      ★★   → 权重 0.05（[P1-2新增] 52周位置：低位吸筹加分）
 *  KDJ_J           弱       ↓      ★    → 权重 0.05（方向反向，降权）
 *  BOLL_POS        弱/无效  ↓      ✗    → 权重 0（降至0，IC无效移除）
 *  VOL_RATIO       无效              ✗   → 权重 0（移除）
 *  BOLL_WIDTH      无效              ✗   → 权重 0（移除）
 * </pre>
 * 注：IC 方向为负（因子值↑→未来收益↓）的指标采用取反处理（等效于超卖为正信号）。
 * [P1-2] 52周新高位置因子：当前价 / 52周最高价，低位（<0.7）加分（距新高空间大），
 *          高位（>0.95）减分（追高风险）。
 * [P3-2] OBV斜率因子：计算近20日OBV的线性斜率，斜率为正且陡峭说明主力持续买入。
 */
public class StockAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(StockAnalyzer.class);

    // =========================================================
    // 各指标权重配置（依据 IC 检验结论调整，合计 1.00）
    //   已移除：VOL_RATIO（无效）、BOLL_WIDTH（无效）、BOLL_POS（弱，降至0）
    //   降权：KDJ（IC 方向反向，信息量弱）、MA（调降为新因子腾空间）
    //   升权：OBV_SLOPE（[P3-2]资金流入中等有效）、WEEK52_POS（[P1-2]低位吸筹）
    //   新增：OBV_SLOPE（权重0.10）、WEEK52_POS（权重0.05）
    // =========================================================
    private static final double WEIGHT_MA         = 0.25;  // MA多头排列（最强因子，调降0.03）
    private static final double WEIGHT_MOMENTUM20 = 0.18;  // 20日动量（中期趋势延续）
    private static final double WEIGHT_MACD       = 0.18;  // MACD（中等有效）
    private static final double WEIGHT_MOMENTUM5  = 0.11;  // 5日动量（短期，谨慎）
    private static final double WEIGHT_OBV_SLOPE  = 0.10;  // [P3-2] OBV斜率（资金持续流入）
    private static final double WEIGHT_RSI        = 0.09;  // RSI（弱，保留超卖信号）
    private static final double WEIGHT_WEEK52_POS = 0.05;  // [P1-2] 52周位置（低位加分）
    private static final double WEIGHT_KDJ        = 0.05;  // KDJ（弱/反向，继续降权）
    private static final double WEIGHT_BOLLINGER  = 0.00;  // 布林位置（IC无效，移除）
    // WEIGHT_VOLUME = 0  VOL_RATIO IC<0.02，移除

    // RSI阈值
    private static final double RSI_OVERSOLD = 30;
    private static final double RSI_OVERBOUGHT = 70;

    /**
     * 对股票进行综合技术分析
     * @param stockCode 股票代码
     * @param stockName 股票名称
     * @param bars K线数据（至少需要60根，建议200根以上）
     * @return 分析结果
     */
    public AnalysisResult analyze(String stockCode, String stockName, List<StockBar> bars) {
        if (bars == null || bars.size() < 60) {
            log.warn("K线数据不足，无法分析: {} (当前{}根，需要至少60根)", stockCode, bars == null ? 0 : bars.size());
            return buildEmptyResult(stockCode, stockName);
        }

        log.info("开始分析股票: {} {} ({}根K线)", stockCode, stockName, bars.size());

        double[] closes = TechnicalIndicator.extractCloses(bars);
        double currentPrice = closes[closes.length - 1];

        // ===== 计算各技术指标 =====
        double[] sma5 = TechnicalIndicator.smaArray(closes, 5);
        double[] sma10 = TechnicalIndicator.smaArray(closes, 10);
        double[] sma20 = TechnicalIndicator.smaArray(closes, 20);
        double[] sma60 = TechnicalIndicator.smaArray(closes, Math.min(60, closes.length));

        double latestMa5 = TechnicalIndicator.lastValid(sma5);
        double latestMa10 = TechnicalIndicator.lastValid(sma10);
        double latestMa20 = TechnicalIndicator.lastValid(sma20);
        double latestMa60 = TechnicalIndicator.lastValid(sma60);

        // MACD
        TechnicalIndicator.MACDResult macdResult = TechnicalIndicator.macd(closes);

        // RSI
        double[] rsi6 = TechnicalIndicator.rsiArray(closes, 6);
        double[] rsi12 = TechnicalIndicator.rsiArray(closes, 12);
        double[] rsi24 = TechnicalIndicator.rsiArray(closes, Math.min(24, closes.length - 1));
        double latestRsi6 = TechnicalIndicator.lastValid(rsi6);
        double latestRsi12 = TechnicalIndicator.lastValid(rsi12);
        double latestRsi24 = TechnicalIndicator.lastValid(rsi24);

        // KDJ
        TechnicalIndicator.KDJResult kdjResult = TechnicalIndicator.kdj(bars);

        // 布林带
        TechnicalIndicator.BollingerResult bollingerResult = TechnicalIndicator.bollinger(closes);

        // 成交量（保留展示，但不再纳入评分）
        double volRatio = TechnicalIndicator.volumeRatio(bars, 5);
        boolean volExpanding = volRatio > 1.5;

        // 动量（IC检验有效因子）
        int n = closes.length;
        double momentum5  = (n >= 6  && closes[n - 6]  > 0) ? (closes[n - 1] - closes[n - 6])  / closes[n - 6]  : 0.0;
        double momentum20 = (n >= 21 && closes[n - 21] > 0) ? (closes[n - 1] - closes[n - 21]) / closes[n - 21] : 0.0;

        // [P3-2] OBV（能量潮）斜率：近20日OBV的线性回归斜率，归一化为相对斜率
        double[] obvArr = TechnicalIndicator.obvArray(bars);
        double obvSlope = calcObvSlope(obvArr, Math.min(20, obvArr.length));

        // [P1-2] 52周位置因子：当前价 / 52周最高价（约250个交易日）
        // 低位（比值<0.7）：距新高空间大，吸筹机会；高位（>0.95）：追高风险
        double week52Position = calcWeek52Position(closes, currentPrice);

        // ===== 综合评分 =====
        Map<String, Integer> scores = calculateScores(
                currentPrice, closes,
                latestMa5, latestMa10, latestMa20, latestMa60,
                macdResult, latestRsi6, latestRsi12, kdjResult,
                bollingerResult, momentum5, momentum20,
                obvSlope, week52Position
        );

        int overallScore = calculateOverallScore(scores);
        AnalysisResult.TrendDirection trend = determineTrend(overallScore, latestMa5, latestMa10, latestMa20, latestMa60);
        TradeSignal.SignalType recommendation = determineRecommendation(overallScore, latestRsi6, kdjResult);

        // ===== 构建结果 =====
        Map<String, Object> indicators = new HashMap<>();
        indicators.put("scores", scores);
        indicators.put("macd_golden_cross", macdResult.isGoldenCross());
        indicators.put("macd_death_cross", macdResult.isDeathCross());
        indicators.put("kdj_golden_cross", kdjResult.isGoldenCross());
        indicators.put("kdj_death_cross", kdjResult.isDeathCross());
        indicators.put("kdj_oversold", kdjResult.isOversold());
        indicators.put("kdj_overbought", kdjResult.isOverbought());
        indicators.put("rsi_oversold", latestRsi6 < RSI_OVERSOLD);
        indicators.put("rsi_overbought", latestRsi6 > RSI_OVERBOUGHT);
        indicators.put("bull_alignment", TechnicalIndicator.isBullishAlignment(latestMa5, latestMa10, latestMa20, latestMa60));
        indicators.put("bear_alignment", TechnicalIndicator.isBearishAlignment(latestMa5, latestMa10, latestMa20, latestMa60));
        indicators.put("bollinger_width", bollingerResult.getBandWidth());
        indicators.put("vol_ratio", volRatio);

        String summary = buildSummary(stockCode, stockName, currentPrice, trend, recommendation,
                overallScore, macdResult, latestRsi6, kdjResult, bollingerResult,
                volRatio, momentum5, momentum20);

        log.info("分析完成: {} {} 评分:{} 趋势:{} 建议:{}",
                stockCode, stockName, overallScore, trend.getDescription(), recommendation.getDescription());

        return AnalysisResult.builder()
                .stockCode(stockCode)
                .stockName(stockName)
                .analysisTime(LocalDateTime.now())
                .currentPrice(currentPrice)
                .ma5(latestMa5)
                .ma10(latestMa10)
                .ma20(latestMa20)
                .ma60(latestMa60)
                .macdDif(macdResult.latestDif())
                .macdDea(macdResult.latestDea())
                .macdBar(macdResult.latestMacd())
                .rsi6(latestRsi6)
                .rsi12(latestRsi12)
                .rsi24(latestRsi24)
                .kdjK(kdjResult.latestK())
                .kdjD(kdjResult.latestD())
                .kdjJ(kdjResult.latestJ())
                .bollingerUpper(bollingerResult.latestUpper())
                .bollingerMiddle(bollingerResult.latestMiddle())
                .bollingerLower(bollingerResult.latestLower())
                .volumeExpanding(volExpanding)
                .volumeRatio(volRatio)
                .overallScore(overallScore)
                .trend(trend)
                .recommendation(recommendation)
                .indicators(indicators)
                .summary(summary)
                .build();
    }

    /**
     * 计算各指标子得分（0-100）
     * <p>
     * 依据 IC 检验结论重构：
     * <ul>
     *   <li>新增 momentum5 / momentum20 得分（IC 有效因子）</li>
     *   <li>[P3-2] 新增 obvSlope 得分（OBV斜率，资金持续流入信号）</li>
     *   <li>[P1-2] 新增 week52Position 得分（52周位置，低位加分）</li>
     *   <li>移除 volume 得分（IC 检验无效，VOL_RATIO |IC|均值 < 0.02）</li>
     *   <li>布林带权重降至0（IC无效，仅保留数据不参与评分）</li>
     * </ul>
     */
    private Map<String, Integer> calculateScores(
            double currentPrice, double[] closes,
            double ma5, double ma10, double ma20, double ma60,
            TechnicalIndicator.MACDResult macd,
            double rsi6, double rsi12,
            TechnicalIndicator.KDJResult kdj,
            TechnicalIndicator.BollingerResult bollinger,
            double momentum5, double momentum20,
            double obvSlope, double week52Position) {

        Map<String, Integer> scores = new HashMap<>();

        // === MACD 得分（IC中等有效，权重0.20）===
        int macdScore = 50;
        double dif = macd.latestDif();
        double dea = macd.latestDea();
        double macdBar = macd.latestMacd();
        if (!Double.isNaN(dif) && !Double.isNaN(dea)) {
            if (dif > 0 && dea > 0) macdScore += 20;
            else if (dif < 0 && dea < 0) macdScore -= 20;
            if (dif > dea) macdScore += 15;
            else macdScore -= 15;
            if (macd.isGoldenCross()) macdScore += 15;
            if (macd.isDeathCross()) macdScore -= 15;
            if (!Double.isNaN(macdBar) && macdBar > 0) macdScore += 10;
            else if (!Double.isNaN(macdBar)) macdScore -= 10;
        }
        scores.put("macd", clamp(macdScore));

        // === RSI 得分（IC弱，权重0.10；仅超卖信号有用）===
        int rsiScore = 50;
        if (!Double.isNaN(rsi6)) {
            if (rsi6 < RSI_OVERSOLD) rsiScore = 72;       // 超卖反弹机会（IC有一定预测力）
            else if (rsi6 > RSI_OVERBOUGHT) rsiScore = 35; // 超买回调（弱信号，不强调卖出）
            else if (rsi6 < 50) rsiScore = 48;
            else rsiScore = 55;
        }
        scores.put("rsi", clamp(rsiScore));

        // === KDJ 得分（IC弱/反向，权重0.06；仅极值信号有参考意义）===
        int kdjScore = 50;
        double k = kdj.latestK();
        double j = kdj.latestJ();
        if (!Double.isNaN(k)) {
            // IC方向为负：KDJ值越高 → 未来收益越低；因此超卖区加分、超买区减分（取反）
            if (kdj.isOversold())  kdjScore = 68;   // 超卖（K<20且D<20）
            else if (kdj.isOverbought()) kdjScore = 38; // 超买（降权，不强卖）
            if (kdj.isGoldenCross()) kdjScore += 12;
            if (kdj.isDeathCross()) kdjScore -= 12;
            if (!Double.isNaN(j) && j < 0) kdjScore += 8;   // J<0极度超卖
            if (!Double.isNaN(j) && j > 100) kdjScore -= 8;  // J>100极度超买
        }
        scores.put("kdj", clamp(kdjScore));

        // === 均线得分（IC最强因子，权重0.28）===
        int maScore = 50;
        if (!Double.isNaN(ma5) && !Double.isNaN(ma10) && !Double.isNaN(ma20)) {
            // 均线多头排列：每条均线计1票，共4票（与IC计算的MA_SCORE对齐）
            int bullCnt = 0;
            if (currentPrice > ma5)  bullCnt++;
            if (ma5 > ma10)          bullCnt++;
            if (ma10 > ma20)         bullCnt++;
            if (!Double.isNaN(ma60) && ma20 > ma60) bullCnt++;
            // 0→25→50→75→100 的线性映射
            maScore = bullCnt * 25;

            // 价格与MA20偏离修正（超涨回调风险）
            if (!Double.isNaN(ma20) && ma20 > 0) {
                double deviation = (currentPrice - ma20) / ma20 * 100;
                if (deviation > 20) maScore -= 8;
                else if (deviation < -15) maScore += 8;
            }
        }
        scores.put("ma", clamp(maScore));

        // === 布林带得分（IC弱/无效，权重0.04；仅保留极值信号）===
        int bollScore = 50;
        if (!Double.isNaN(bollinger.latestUpper())) {
            double upper = bollinger.latestUpper();
            double lower = bollinger.latestLower();
            double bandWidth = upper - lower;
            if (bandWidth > 0) {
                double posInBand = (currentPrice - lower) / bandWidth;
                // 仅极值区（<0.15 超跌或 >0.85 超涨）给信号，其余保持中性
                if (currentPrice < lower) bollScore = 65;       // 跌破下轨，均值回归机会
                else if (currentPrice > upper) bollScore = 40;   // 突破上轨（弱信号）
                else if (posInBand < 0.15) bollScore = 58;
                else if (posInBand > 0.85) bollScore = 44;
            }
        }
        scores.put("bollinger", clamp(bollScore));

        // === 5日动量得分（IC中等，权重0.12）===
        // IC为正向（动量越强 → 未来收益越高），但短期会有均值回归
        int mom5Score;
        if (momentum5 > 0.05)       mom5Score = 68;  // 近5日涨幅>5%，短期动量强
        else if (momentum5 > 0.02)  mom5Score = 60;
        else if (momentum5 > 0)     mom5Score = 54;
        else if (momentum5 > -0.02) mom5Score = 47;
        else if (momentum5 > -0.05) mom5Score = 40;
        else                        mom5Score = 32;  // 近5日跌幅>5%，动量弱
        scores.put("momentum5", mom5Score);

        // === 20日动量得分（IC最强之一，权重0.18）===
        // 中期趋势延续性最强
        int mom20Score;
        if (momentum20 > 0.15)       mom20Score = 78;  // 近20日涨幅>15%，强势
        else if (momentum20 > 0.08)  mom20Score = 68;
        else if (momentum20 > 0.03)  mom20Score = 60;
        else if (momentum20 > 0)     mom20Score = 53;
        else if (momentum20 > -0.05) mom20Score = 44;
        else if (momentum20 > -0.10) mom20Score = 36;
        else                         mom20Score = 26;  // 近20日跌幅>10%，趋势弱
        scores.put("momentum20", mom20Score);

        // === [P3-2] OBV斜率得分（权重0.10）===
        // OBV斜率 > 0 且陡峭：主力持续流入，是可靠的多头信号
        // 斜率为负且陡峭：主力持续流出，空头风险高
        // obvSlope 已经过归一化（相对于OBV均值，单位：%/日）
        int obvScore;
        if (obvSlope > 2.0)       obvScore = 78;  // OBV日均增速>2%，主力强力买入
        else if (obvSlope > 0.8)  obvScore = 68;
        else if (obvSlope > 0.2)  obvScore = 60;
        else if (obvSlope >= 0)   obvScore = 53;  // 轻微正斜率，中性偏多
        else if (obvSlope > -0.5) obvScore = 44;  // 轻微负斜率，中性偏空
        else if (obvSlope > -1.5) obvScore = 36;
        else                      obvScore = 26;  // OBV持续下行，主力出货
        scores.put("obvSlope", clamp(obvScore));

        // === [P1-2] 52周位置得分（权重0.05）===
        // week52Position = 当前价 / 52周最高价（0~1之间）
        //   < 0.6（低位）：距历史高点空间大，低吸机会，加分
        //   0.6~0.85（中位）：正常区间，中性
        //   > 0.9（高位接近新高）：追高风险，减分
        //   > 0.98（创新高区域）：突破行情，加分（趋势延续）
        int week52Score;
        if (week52Position <= 0)    week52Score = 50; // 数据不足，中性
        else if (week52Position > 0.98) week52Score = 70; // 突破52周新高，强势趋势延续
        else if (week52Position > 0.90) week52Score = 38; // 高位追高风险
        else if (week52Position > 0.75) week52Score = 50; // 中位，中性
        else if (week52Position > 0.60) week52Score = 60; // 中低位，有吸筹价值
        else                            week52Score = 68; // 低位（<60%）：低估值+吸筹机会
        scores.put("week52Position", clamp(week52Score));

        return scores;
    }

    /**
     * 加权计算综合得分（依据 IC 检验结论调整权重）
     * [P1-2][P3-2] 新增 obvSlope 和 week52Position 两个因子的加权
     */
    private int calculateOverallScore(Map<String, Integer> scores) {
        double weighted =
                scores.getOrDefault("ma", 50)            * WEIGHT_MA         +
                scores.getOrDefault("momentum20", 50)    * WEIGHT_MOMENTUM20 +
                scores.getOrDefault("macd", 50)          * WEIGHT_MACD       +
                scores.getOrDefault("momentum5", 50)     * WEIGHT_MOMENTUM5  +
                scores.getOrDefault("obvSlope", 50)      * WEIGHT_OBV_SLOPE  +
                scores.getOrDefault("rsi", 50)           * WEIGHT_RSI        +
                scores.getOrDefault("week52Position", 50) * WEIGHT_WEEK52_POS +
                scores.getOrDefault("kdj", 50)           * WEIGHT_KDJ        +
                scores.getOrDefault("bollinger", 50)     * WEIGHT_BOLLINGER;
        return (int) Math.round(weighted);
    }

    /**
     * [P3-2] 计算 OBV 近 N 日的线性斜率，归一化为相对增速（%/日）
     * <p>
     * 原理：用最小二乘法拟合 OBV 时序的线性趋势，斜率为正说明 OBV 持续上升（资金流入）；
     * 为消除不同股票 OBV 绝对值量级差异，将斜率除以 OBV 均值的绝对值归一化。
     *
     * @param obvArr OBV 数组（时间升序）
     * @param window 计算窗口（取最近 window 个点）
     * @return 归一化 OBV 斜率（%/日），正值=主力买入，负值=主力出货
     */
    private double calcObvSlope(double[] obvArr, int window) {
        if (obvArr == null || obvArr.length < 4 || window < 4) return 0.0;
        int n = Math.min(window, obvArr.length);
        int start = obvArr.length - n;

        // 最小二乘法：计算斜率 k = (n*Σxy - Σx*Σy) / (n*Σx² - (Σx)²)
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        double sumAbsY = 0;
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = obvArr[start + i];
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
            sumAbsY += Math.abs(y);
        }
        double denominator = n * sumX2 - sumX * sumX;
        if (denominator == 0) return 0.0;
        double slope = (n * sumXY - sumX * sumY) / denominator;

        // 归一化：斜率 / OBV均值绝对值 * 100（单位：% per bar）
        double avgAbsY = sumAbsY / n;
        if (avgAbsY < 1) return 0.0; // OBV太小，不可信
        return slope / avgAbsY * 100.0;
    }

    /**
     * [P1-2] 计算 52 周位置因子（当前价 / 52周最高价）
     * <p>
     * 52周最高价取最近 min(250, n) 根 K 线中的最高收盘价。
     * 若 K 线数量不足，降级取现有数据的最高点。
     *
     * @param closes    收盘价数组（时间升序）
     * @param currentPrice 当前价格
     * @return 位置比率 [0, 1+]，> 1 说明当前价超过历史最高
     */
    private double calcWeek52Position(double[] closes, double currentPrice) {
        if (closes == null || closes.length < 5 || currentPrice <= 0) return 0.0;
        int window = Math.min(250, closes.length);
        int start = closes.length - window;
        double high52 = 0;
        for (int i = start; i < closes.length; i++) {
            if (closes[i] > high52) high52 = closes[i];
        }
        if (high52 <= 0) return 0.0;
        return currentPrice / high52;
    }

    /**
     * 判断趋势方向
     */
    private AnalysisResult.TrendDirection determineTrend(
            int score, double ma5, double ma10, double ma20, double ma60) {

        boolean bullish = TechnicalIndicator.isBullishAlignment(ma5, ma10, ma20,
                Double.isNaN(ma60) ? ma20 - 1 : ma60);
        boolean bearish = TechnicalIndicator.isBearishAlignment(ma5, ma10, ma20,
                Double.isNaN(ma60) ? ma20 + 1 : ma60);

        if (score >= 70 && bullish) return AnalysisResult.TrendDirection.STRONG_UP;
        if (score >= 60) return AnalysisResult.TrendDirection.UP;
        if (score <= 30 && bearish) return AnalysisResult.TrendDirection.STRONG_DOWN;
        if (score <= 40) return AnalysisResult.TrendDirection.DOWN;
        return AnalysisResult.TrendDirection.SIDEWAYS;
    }

    /**
     * 确定操作建议
     */
    private TradeSignal.SignalType determineRecommendation(
            int score, double rsi6, TechnicalIndicator.KDJResult kdj) {

        if (score >= 75 && rsi6 < RSI_OVERBOUGHT && !kdj.isOverbought()) {
            return TradeSignal.SignalType.STRONG_BUY;
        }
        if (score >= 60) return TradeSignal.SignalType.BUY;
        if (score <= 25 && rsi6 > RSI_OVERSOLD && !kdj.isOversold()) {
            return TradeSignal.SignalType.STRONG_SELL;
        }
        if (score <= 40) return TradeSignal.SignalType.SELL;
        return TradeSignal.SignalType.HOLD;
    }

    /**
     * 生成分析摘要文本（包含动量指标展示）
     */
    private String buildSummary(String code, String name, double price,
                                  AnalysisResult.TrendDirection trend,
                                  TradeSignal.SignalType recommendation,
                                  int score,
                                  TechnicalIndicator.MACDResult macd,
                                  double rsi6,
                                  TechnicalIndicator.KDJResult kdj,
                                  TechnicalIndicator.BollingerResult bollinger,
                                  double volRatio,
                                  double momentum5, double momentum20) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【%s %s】当前价: %.2f | 综合评分: %d/100 | 趋势: %s | 建议: %s\n",
                code, name, price, score, trend.getDescription(), recommendation.getDescription()));

        sb.append(String.format("MACD: DIF=%.4f, DEA=%.4f, BAR=%.4f",
                macd.latestDif(), macd.latestDea(), macd.latestMacd()));
        if (macd.isGoldenCross()) sb.append(" ✅金叉");
        if (macd.isDeathCross()) sb.append(" ❌死叉");
        sb.append("\n");

        sb.append(String.format("RSI6: %.1f", rsi6));
        if (rsi6 < RSI_OVERSOLD) sb.append(" (超卖，注意反弹机会)");
        else if (rsi6 > RSI_OVERBOUGHT) sb.append(" (超买，注意回调风险)");
        sb.append("\n");

        sb.append(String.format("KDJ: K=%.1f, D=%.1f, J=%.1f",
                kdj.latestK(), kdj.latestD(), kdj.latestJ()));
        if (kdj.isOversold()) sb.append(" (超卖)");
        if (kdj.isOverbought()) sb.append(" (超买)");
        if (kdj.isGoldenCross()) sb.append(" ✅金叉");
        if (kdj.isDeathCross()) sb.append(" ❌死叉");
        sb.append("\n");

        sb.append(String.format("布林带: 上=%.2f 中=%.2f 下=%.2f (带宽%.1f%%)\n",
                bollinger.latestUpper(), bollinger.latestMiddle(),
                bollinger.latestLower(), bollinger.getBandWidth()));

        // 动量展示（IC有效因子）
        sb.append(String.format("动量: 5日=%+.2f%%  20日=%+.2f%%",
                momentum5 * 100, momentum20 * 100));
        if (momentum20 > 0.08) sb.append(" ↑强势");
        else if (momentum20 < -0.10) sb.append(" ↓弱势");
        sb.append("\n");

        sb.append(String.format("成交量比率: %.2f%s（参考，不纳入评分）",
                volRatio, volRatio > 1.5 ? " (放量)" : (volRatio < 0.7 ? " (缩量)" : "")));

        return sb.toString();
    }

    /**
     * 对历史某根K线（截面）进行分析，用于回测和因子验证
     * <p>
     * 与 {@link #analyze} 的区别：
     * <ul>
     *   <li>只使用截面日及之前的 K 线（bars 的最后一根即为截面日）</li>
     *   <li>不调用实时接口，适合批量历史截面计算</li>
     * </ul>
     *
     * @param stockCode 股票代码
     * @param stockName 股票名称
     * @param barsUpToDate 截面日及之前的全量 K 线（升序，最后一根为截面日）
     * @return 分析结果（analysisTime 设置为截面日当天 00:00）
     */
    public AnalysisResult analyzeAtBar(String stockCode, String stockName,
                                        List<StockBar> barsUpToDate) {
        return analyze(stockCode, stockName, barsUpToDate);
    }

    private AnalysisResult buildEmptyResult(String stockCode, String stockName) {
        return AnalysisResult.builder()
                .stockCode(stockCode)
                .stockName(stockName)
                .analysisTime(LocalDateTime.now())
                .trend(AnalysisResult.TrendDirection.SIDEWAYS)
                .recommendation(TradeSignal.SignalType.HOLD)
                .overallScore(50)
                .summary("数据不足，无法完成分析")
                .build();
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    // 辅助方法：将double数组转为Double列表
    private Double[] boxes(double[] arr) {
        Double[] result = new Double[arr.length];
        for (int i = 0; i < arr.length; i++) result[i] = arr[i];
        return result;
    }
}


package com.stocktrader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 技术分析结果模型
 * 汇总各技术指标的分析结论
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResult {

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 分析时间 */
    private LocalDateTime analysisTime;

    /** 当前价格 */
    private double currentPrice;

    // ========== 移动均线 ==========
    /** MA5 */
    private double ma5;
    /** MA10 */
    private double ma10;
    /** MA20 */
    private double ma20;
    /** MA60 */
    private double ma60;

    // ========== MACD ==========
    /** MACD DIF值 */
    private double macdDif;
    /** MACD DEA值 */
    private double macdDea;
    /** MACD柱值 */
    private double macdBar;

    // ========== RSI ==========
    /** RSI6 */
    private double rsi6;
    /** RSI12 */
    private double rsi12;
    /** RSI24 */
    private double rsi24;

    // ========== KDJ ==========
    /** KDJ K值 */
    private double kdjK;
    /** KDJ D值 */
    private double kdjD;
    /** KDJ J值 */
    private double kdjJ;

    // ========== 布林带 ==========
    /** 布林带上轨 */
    private double bollingerUpper;
    /** 布林带中轨（MA20） */
    private double bollingerMiddle;
    /** 布林带下轨 */
    private double bollingerLower;

    // ========== 成交量 ==========
    /** 成交量是否放量（较5日均量） */
    private boolean volumeExpanding;
    /** 成交量比率（当日/5日均量） */
    private double volumeRatio;

    // ========== 综合评分 ==========
    /** 综合评分 0-100 */
    private int overallScore;

    /** 趋势判断 */
    private TrendDirection trend;

    /** 操作建议 */
    private TradeSignal.SignalType recommendation;

    /** 各指标详细信息 */
    private Map<String, Object> indicators;

    /** 分析摘要 */
    private String summary;

    public enum TrendDirection {
        STRONG_UP("强势上涨"),
        UP("上涨"),
        SIDEWAYS("横盘震荡"),
        DOWN("下跌"),
        STRONG_DOWN("强势下跌");

        private final String description;

        TrendDirection(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}


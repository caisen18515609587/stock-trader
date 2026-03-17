package com.stocktrader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 股票基本面因子数据
 * <p>
 * 来源：Python Tushare 服务 /fundamental 接口
 * <p>
 * 可用于：
 * 1. 基本面辅助过滤（配合技术面选股，排除高估值/亏损/高负债个股）
 * 2. 量化因子 IC 检验（验证各因子对未来收益的预测能力）
 * 3. 多因子选股模型（技术因子 + 基本面因子综合评分）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundamentalFactor {

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 数据日期 */
    private LocalDate dataDate;

    // ========== 估值因子 ==========
    /** 市盈率(TTM) —— 越低越便宜（0 表示无效/亏损）*/
    private double peTtm;

    /** 市净率 —— 越低越便宜 */
    private double pb;

    /** 市销率(TTM) —— 收入估值 */
    private double psTtm;

    /** 股息率(%) —— 越高越具价值 */
    private double dvRatio;

    /** 总市值（万元）*/
    private double totalMv;

    /** 流通市值（万元）*/
    private double circMv;

    // ========== 盈利因子 ==========
    /** 净资产收益率ROE(%) —— 越高越好，>15%为优质 */
    private double roe;

    /** 总资产收益率ROA(%) */
    private double roa;

    /** 毛利率(%) —— 行业溢价能力，越高越好 */
    private double grossMargin;

    // ========== 成长因子 ==========
    /** 营收同比增速(%) —— 成长驱动力 */
    private double revenueYoy;

    /** 净利润同比增速(%) —— 盈利成长 */
    private double profitYoy;

    // ========== 安全因子 ==========
    /** 资产负债率(%) —— 越低财务越安全，>70%需关注 */
    private double debtRatio;

    /** 财报期（如 20241231）*/
    private String reportPeriod;

    // ========== 综合评分 ==========
    /** 基本面综合评分（0-100，Python服务计算）*/
    private int fundamentalScore;

    // ========== 衍生因子（本地计算）==========

    /**
     * 是否为高质量股（ROE > 15 且利润正增长 且负债率 < 70）
     */
    public boolean isHighQuality() {
        return roe > 15 && profitYoy > 0 && debtRatio < 70;
    }

    /**
     * 是否低估值（PE < 20 且 PB < 3，排除亏损股）
     */
    public boolean isUndervalued() {
        return peTtm > 0 && peTtm < 20 && pb > 0 && pb < 3;
    }

    /**
     * 是否财务危险（负债率 > 80% 或 利润连续下滑）
     */
    public boolean isFinanciallyRisky() {
        return debtRatio > 80 || (profitYoy < -20 && roe < 0);
    }

    /**
     * 技术+基本面综合评分（合并两种评分）
     * @param techScore 技术面评分（0-100）
     * @param techWeight 技术面权重（0-1，如 0.6）
     * @return 综合评分
     */
    public int combinedScore(int techScore, double techWeight) {
        double fundWeight = 1 - techWeight;
        return (int) (techScore * techWeight + fundamentalScore * fundWeight);
    }

    /**
     * 对比另一个股票的估值吸引力
     * @return 正数表示本股票估值更优
     */
    public double valuationScore() {
        // 简单综合估值：低PE+低PB+高股息
        double score = 0;
        if (peTtm > 0 && peTtm < 100) score += (100 - peTtm) / 100.0 * 40;
        if (pb > 0) score += Math.max(0, (10 - pb) / 10.0) * 30;
        score += Math.min(dvRatio * 5, 30); // 股息率上限30分
        return score;
    }

    @Override
    public String toString() {
        return String.format("[%s %s] PE=%.1f PB=%.2f ROE=%.1f%% 净利增=%.1f%% 负债=%.1f%% 评分=%d",
                stockCode, stockName, peTtm, pb, roe, profitYoy, debtRatio, fundamentalScore);
    }
}


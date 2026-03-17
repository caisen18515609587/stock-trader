package com.stocktrader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 策略配置模型
 * <p>
 * 支持三种策略类型：
 * 1. 中长期策略（MEDIUM_LONG）：根据资金量自动生成，持仓周期较长，止损宽松，适合稳健型
 * 2. 短线做T策略（DAY_TRADE）：根据资金量自动生成，止损严格，适合活跃型
 * 3. 自定义策略（CUSTOM）：用户自己填写所有参数
 * <p>
 * 资金量对策略的影响（自动生成）：
 * - 小资金（< 10万）：仓位集中，止损宽松，追求高收益
 * - 中等资金（10-50万）：均衡配置，标准参数
 * - 大资金（> 50万）：分散持仓，止损严格，稳健优先
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StrategyConfig {

    // ===== 通用参数 =====

    /** 止损比例（如0.05表示5%） */
    private double stopLossPercent;

    /** 减仓止盈比例（触发减仓50%） */
    private double takeProfitHalfPercent;

    /** 清仓止盈比例（触发清仓100%） */
    private double takeProfitFullPercent;

    /** 单只股票最大仓位比例（如0.30表示30%） */
    private double maxPositionRatio;

    /** 最大持股数量 */
    private int maxPositions;

    /** 选股Top N（每次选出前N支） */
    private int topN;

    /** 选股最低评分（0-100） */
    private int minScore;

    /** 扫描间隔（分钟） */
    private int scanIntervalMin;

    /**
     * 扫描间隔（秒）
     * 当此值 > 0 时，优先使用秒级间隔（覆盖 scanIntervalMin）
     * 适用于日内高频做T策略，如每20秒扫描一次
     * 建议范围：10~120秒；0 表示不启用（默认）
     */
    private int scanIntervalSeconds;

    // ===== 中长期策略专属 =====

    /** 最短持仓天数（中长期有效） */
    private int minHoldDays;

    /** 均线周期（中长期：MA20/MA60/MA120） */
    private String maPeriods;

    // ===== 做T策略专属 =====

    /** 做T减仓触发盈利比例 */
    private double doTProfitPercent;

    /** 尾盘做T截止时间（格式HH:mm，如14:45） */
    private String doTCutoffTime;

    // ===== 追踪止盈（TrailingStop）专属 =====

    /**
     * [P2-3 优化] 追踪止盈回撤容忍度（Trailing Stop Pullback）
     * <p>
     * 含义：持仓期间从最高价回落超过此比例时，触发减仓50%（锁利操作）。
     * 默认值 0.03（3%）适合大多数股票；科创/创业板波动大可配置为 0.05（5%）。
     * <p>
     * 配置建议：
     *   - 中长期策略（波动容忍较大）：0.04~0.05
     *   - 短线做T策略（快速锁利）：0.02~0.03
     *   - 换股策略：0.03（默认）
     * </p>
     * 值为 0 时，系统使用默认值 0.03（向后兼容）。
     */
    private double trailingStopPullback;

    // ===== 自定义策略文字描述 =====

    /** 用户自定义策略描述（中文，系统据此生成参数） */
    private String customDescription;

    /**
     * 根据资金量自动生成中长期策略配置
     * <p>
     * 中长期策略特点：
     * - 止损宽：允许较大波动（7%-10%）
     * - 持仓时间长：至少持有5-20天
     * - 选股严格：评分要求高（70+）
     * - 持股少而精：2-3只
     */
    public static StrategyConfig buildMediumLong(double capital) {
        StrategyConfigBuilder b = StrategyConfig.builder();

        if (capital < 100000) {
            // 小资金（< 10万）：集中持仓2只，追求弹性
            b.stopLossPercent(0.07)
             .takeProfitHalfPercent(0.15)
             .takeProfitFullPercent(0.25)
             .maxPositionRatio(0.45)
             .maxPositions(2)
             .topN(2)
             .minScore(65)
             .scanIntervalMin(30)
             .minHoldDays(5)
             .maPeriods("MA5,MA20,MA60")
             .trailingStopPullback(0.04); // 中长期波动容忍稍宽
        } else if (capital < 500000) {
            // 中等资金（10-50万）：持仓3只，均衡。3×30%=90%，留10%缓冲
            b.stopLossPercent(0.08)
             .takeProfitHalfPercent(0.12)
             .takeProfitFullPercent(0.20)
             .maxPositionRatio(0.30)
             .maxPositions(3)
             .topN(3)
             .minScore(68)
             .scanIntervalMin(30)
             .minHoldDays(10)
             .maPeriods("MA10,MA20,MA60")
             .trailingStopPullback(0.04); // 中长期波动容忍稍宽
        } else {
            // 大资金（> 50万）：分散持仓5只，稳健。5×20%=100%，严格控制单只风险
            b.stopLossPercent(0.06)
             .takeProfitHalfPercent(0.10)
             .takeProfitFullPercent(0.18)
             .maxPositionRatio(0.20)
             .maxPositions(5)
             .topN(5)
             .minScore(70)
             .scanIntervalMin(60)
             .minHoldDays(20)
             .maPeriods("MA20,MA60,MA120")
             .trailingStopPullback(0.05); // 大资金中长期，给更大的回撤空间
        }
        return b.build();
    }

    /**
     * 根据资金量自动生成做T策略配置
     * <p>
     * 做T策略特点：
     * - 止损严：3%-5%快速止损
     * - 持仓短：当日或次日做T
     * - 活跃扫描：每5-15分钟
     * - 仓位灵活：可快速进出
     */
    public static StrategyConfig buildDayTrade(double capital) {
        StrategyConfigBuilder b = StrategyConfig.builder();

        if (capital < 100000) {
            // 小资金（< 10万）：激进做T，高频
            // 启用秒级扫描（20秒/次），适合日内做T快速捕捉高低点
            b.stopLossPercent(0.03)
             .takeProfitHalfPercent(0.05)
             .takeProfitFullPercent(0.08)
             .maxPositionRatio(0.50)
             .maxPositions(1)
             .topN(1)
             .minScore(60)
             .scanIntervalMin(5)
             .scanIntervalSeconds(20)
             .doTProfitPercent(0.015)
             .doTCutoffTime("14:45")
             .trailingStopPullback(0.02); // 短线做T快速锁利，回撤容忍紧
        } else if (capital < 500000) {
            // 中等资金（10-50万）：稳健做T。3×30%=90%，留10%缓冲
            b.stopLossPercent(0.04)
             .takeProfitHalfPercent(0.06)
             .takeProfitFullPercent(0.10)
             .maxPositionRatio(0.30)
             .maxPositions(3)
             .topN(3)
             .minScore(60)
             .scanIntervalMin(10)
             .doTProfitPercent(0.025)
             .doTCutoffTime("14:45")
             .trailingStopPullback(0.03); // 做T标准回撤容忍3%
        } else {
            // 大资金（> 50万）：保守做T，低频。4×25%=100%，严格控制单只风险
            b.stopLossPercent(0.03)
             .takeProfitHalfPercent(0.05)
             .takeProfitFullPercent(0.08)
             .maxPositionRatio(0.25)
             .maxPositions(4)
             .topN(4)
             .minScore(65)
             .scanIntervalMin(15)
             .doTProfitPercent(0.02)
             .doTCutoffTime("14:30")
             .trailingStopPullback(0.03); // 保守做T，标准回撤容忍3%
        }
        return b.build();
    }

    /**
     * 换股（汰弱留强）策略配置
     * <p>
     * 特点：
     * - 持仓时间灵活（无最短持仓要求）
     * - 满仓时每 3 轮定频扫描大盘，发现更优标的则换仓
     * - 换仓条件严格：新股评分比最弱持仓高 15 分，趋势不能下跌，24h冷却期
     * - 仓位置换不超过 -5% 浮亏持仓（深度亏损由止损逻辑处理）
     */
    public static StrategyConfig buildSwapStrong(double capital) {
        StrategyConfigBuilder b = StrategyConfig.builder();

        if (capital < 100000) {
            // 小资金：集中持仓3只，积极换仓
            b.stopLossPercent(0.05)
             .takeProfitHalfPercent(0.08)
             .takeProfitFullPercent(0.15)
             .maxPositionRatio(0.33)
             .maxPositions(3)
             .topN(3)
             .minScore(62)
             .scanIntervalMin(10)
             .minHoldDays(0)
             .doTProfitPercent(0.0)
             .doTCutoffTime("14:45")
             .trailingStopPullback(0.03); // 换股策略标准回撤容忍
        } else if (capital < 500000) {
            // 中等资金：持仓3~4只，均衡换仓
            b.stopLossPercent(0.05)
             .takeProfitHalfPercent(0.08)
             .takeProfitFullPercent(0.15)
             .maxPositionRatio(0.30)
             .maxPositions(3)
             .topN(3)
             .minScore(65)
             .scanIntervalMin(10)
             .minHoldDays(0)
             .doTProfitPercent(0.0)
             .doTCutoffTime("14:45")
             .trailingStopPullback(0.03); // 换股策略标准回撤容忍
        } else {
            // 大资金：持仓5只，分散换仓，稳健
            b.stopLossPercent(0.05)
             .takeProfitHalfPercent(0.08)
             .takeProfitFullPercent(0.15)
             .maxPositionRatio(0.20)
             .maxPositions(5)
             .topN(5)
             .minScore(68)
             .scanIntervalMin(15)
             .minHoldDays(0)
             .doTProfitPercent(0.0)
             .doTCutoffTime("14:45")
             .trailingStopPullback(0.04); // 大资金换股，稍宽容忍度
        }
        return b.build();
    }

    /**
     * 解析自定义策略描述，生成参数
     * （简化实现：从描述中提取关键词映射到参数）
     */
    public static StrategyConfig buildCustom(String description, double capital) {
        // 先用中长期作为默认基础
        StrategyConfig base = buildMediumLong(capital);
        base.setCustomDescription(description);

        String desc = description.toLowerCase();

        // 根据描述关键词覆盖参数
        if (desc.contains("激进") || desc.contains("高风险") || desc.contains("高收益")) {
            base.setStopLossPercent(0.08);
            base.setTakeProfitFullPercent(0.30);
            base.setMaxPositionRatio(0.50);
        } else if (desc.contains("保守") || desc.contains("稳健") || desc.contains("低风险")) {
            base.setStopLossPercent(0.05);
            base.setTakeProfitFullPercent(0.15);
            base.setMaxPositionRatio(0.20);
        }

        if (desc.contains("短线") || desc.contains("做t") || desc.contains("做T")) {
            base.setScanIntervalMin(10);
            base.setMinHoldDays(1);
            base.setDoTProfitPercent(0.02);
        } else if (desc.contains("长期") || desc.contains("价值")) {
            base.setScanIntervalMin(60);
            base.setMinHoldDays(30);
        }

        if (desc.contains("集中") || desc.contains("重仓")) {
            base.setMaxPositions(2);
            base.setTopN(2);
            base.setMaxPositionRatio(0.50);
        } else if (desc.contains("分散") || desc.contains("多只")) {
            base.setMaxPositions(5);
            base.setTopN(5);
            base.setMaxPositionRatio(0.20);
        }

        return base;
    }

    /**
     * 获取追踪止盈回撤容忍度（有效值）
     * 值为0时返回默认3%，向后兼容未配置此字段的旧策略
     */
    public double getEffectiveTrailingStopPullback() {
        return trailingStopPullback > 0 ? trailingStopPullback : 0.03;
    }

    /** 获取策略参数的可读描述 */
    public String getSummary() {
        return String.format(
            "止损%.0f%% | 减仓止盈%.0f%% | 清仓止盈%.0f%% | 最大仓位%.0f%% | 最多持股%d只 | 扫描%dmin | 追踪回撤%.0f%%",
            stopLossPercent * 100, takeProfitHalfPercent * 100,
            takeProfitFullPercent * 100, maxPositionRatio * 100,
            maxPositions, scanIntervalMin,
            getEffectiveTrailingStopPullback() * 100
        );
    }
}


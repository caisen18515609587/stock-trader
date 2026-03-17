package com.stocktrader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 已平仓记录 - 记录每笔完整买卖交易的盈亏情况
 * 在 Portfolio.executeSell 时生成，持久化在账户 JSON 中
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClosedPosition {

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 卖出数量（本次平仓数量，可能是减仓） */
    private int quantity;

    /** 买入均价（成本价） */
    private double avgCost;

    /** 卖出价格 */
    private double sellPrice;

    /** 本次交易总费用（手续费+印花税+过户费） */
    private double totalFee;

    /** 已实现盈亏（元）= (卖出价 - 成本价) × 数量 - 总费用 */
    private double realizedPnl;

    /** 盈亏率（%）= realizedPnl / (avgCost × quantity) × 100 */
    private double realizedPnlRate;

    /** 平仓原因（止损/止盈/卖出信号等） */
    private String closeReason;

    /** 买入时间（首次建仓时间） */
    private LocalDateTime buyTime;

    /** 卖出时间 */
    private LocalDateTime sellTime;
}


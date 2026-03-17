package com.stocktrader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 持仓模型 - 描述当前持有的某支股票的仓位信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Position {

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 持仓数量（股） */
    private int quantity;

    /** 可用数量（T+1规则，今日买入当日不可卖） */
    private int availableQuantity;

    /** 最近一次买入日期（用于 T+1 判断） */
    private LocalDate lastBuyDate;

    /** 成本价（均价） */
    private double avgCost;

    /** 当前价格 */
    private double currentPrice;

    /** 市值 */
    private double marketValue;

    /** 持仓收益（元） */
    private double profit;

    /** 持仓收益率（%） */
    private double profitRate;

    /** 首次买入时间 */
    private LocalDateTime firstBuyTime;

    /** 最近操作时间 */
    private LocalDateTime lastUpdateTime;

    /**
     * 持仓期间最高价（用于追踪止盈：止盈线随价格上涨自动上移）
     * 每次更新 currentPrice 时，若价格创新高则同步更新此字段。
     */
    private double highestPrice;

    /**
     * 买入时由策略层计算的 ATR 动态止损价（绝对价格）。
     * <p>
     * 来源：executeBuy() 成功后从 signal.getStopLossPrice() 写入。
     * 作用：分钟级快速止损（quickCheckOnePosition）可直接读取此价，
     *       与固定比例止损价取较高者（即止损更紧的那个），
     *       保证两条止损路径标准完全一致，消除原来「策略层 ATR 止损」vs「分钟级固定止损」的盲区。
     * 为 0 时表示未记录（旧持仓或策略未给出），降级为固定比例止损。
     */
    private double atrStopPrice;

    /**
     * 计算当前市值
     */
    public double calculateMarketValue() {
        return quantity * currentPrice;
    }

    /**
     * 计算浮动盈亏
     */
    public double calculateProfit() {
        return (currentPrice - avgCost) * quantity;
    }

    /**
     * 计算收益率
     */
    public double calculateProfitRate() {
        if (avgCost == 0) return 0;
        return (currentPrice - avgCost) / avgCost * 100;
    }

    /**
     * 更新持仓信息（买入）
     * @param buyPrice 买入价格
     * @param buyQuantity 买入数量
     */
    public void addPosition(double buyPrice, int buyQuantity) {
        double totalCost = avgCost * quantity + buyPrice * buyQuantity;
        quantity += buyQuantity;
        if (quantity > 0) {
            avgCost = totalCost / quantity;
        }
        // 初始化/更新持仓最高价（买入价若高于历史最高则更新）
        if (highestPrice <= 0 || buyPrice > highestPrice) {
            highestPrice = buyPrice;
        }
        lastUpdateTime = LocalDateTime.now();
    }

    /**
     * 更新当前价格，同步维护持仓期间最高价（用于追踪止盈）
     */
    public void updateCurrentPrice(double price) {
        this.currentPrice = price;
        if (price > highestPrice) {
            highestPrice = price;
        }
    }

    /**
     * 更新持仓信息（卖出）
     * @param sellQuantity 卖出数量
     */
    public void reducePosition(int sellQuantity) {
        quantity -= sellQuantity;
        availableQuantity = Math.max(0, availableQuantity - sellQuantity);
        lastUpdateTime = LocalDateTime.now();
    }
}


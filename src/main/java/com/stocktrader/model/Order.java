package com.stocktrader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 交易订单模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    /** 订单唯一ID */
    private String orderId;

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 订单类型：BUY-买入, SELL-卖出 */
    private OrderType orderType;

    /** 订单状态 */
    private OrderStatus status;

    /** 委托价格（-1表示市价） */
    private double price;

    /** 委托数量（股） */
    private int quantity;

    /** 成交价格 */
    private double filledPrice;

    /** 成交数量 */
    private int filledQuantity;

    /** 交易金额 */
    private double amount;

    /** 手续费 */
    private double commission;

    /** 印花税（卖出时收取） */
    private double stampTax;

    /** 过户费 */
    private double transferFee;

    /** 总费用（手续费+印花税+过户费） */
    private double totalFee;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 成交时间 */
    private LocalDateTime filledTime;

    /** 策略来源：标识哪个策略产生的订单 */
    private String strategyName;

    /** 备注 */
    private String remark;

    /**
     * 计算实际总成本（买入：金额+费用；卖出：金额-费用）
     */
    public double getNetAmount() {
        if (orderType == OrderType.BUY) {
            return amount + totalFee;
        } else {
            return amount - totalFee;
        }
    }

    public enum OrderType {
        BUY("买入"),
        SELL("卖出");

        private final String description;

        OrderType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public enum OrderStatus {
        PENDING("待提交"),
        SUBMITTED("已提交"),
        PARTIAL_FILLED("部分成交"),
        FILLED("全部成交"),
        CANCELLED("已撤销"),
        REJECTED("已拒绝"),
        FAILED("失败");

        private final String description;

        OrderStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}


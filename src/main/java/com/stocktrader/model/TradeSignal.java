package com.stocktrader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 交易信号模型
 * 由分析引擎产生，指示买入或卖出操作
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeSignal {

    /** 信号ID */
    private String signalId;

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 信号类型 */
    private SignalType signalType;

    /** 信号强度 0-100 */
    private int strength;

    /** 建议价格 */
    private double suggestedPrice;

    /** 建议数量 */
    private int suggestedQuantity;

    /** 建议仓位比例 0-1 */
    private double positionRatio;

    /** 止损价格 */
    private double stopLossPrice;

    /** 止盈价格 */
    private double takeProfitPrice;

    /** 产生信号的策略名称 */
    private String strategyName;

    /** 信号产生时间 */
    private LocalDateTime signalTime;

    /** 信号有效期（分钟，0表示当日有效） */
    private int validMinutes;

    /** 信号依据描述 */
    private String reason;

    /** 是否已被处理 */
    private boolean processed;

    public enum SignalType {
        BUY("买入"),
        SELL("卖出"),
        HOLD("持有"),
        STRONG_BUY("强烈买入"),
        STRONG_SELL("强烈卖出"),
        STOP_LOSS("止损"),
        TAKE_PROFIT("止盈");

        private final String description;

        SignalType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }

        public boolean isBuySignal() {
            return this == BUY || this == STRONG_BUY;
        }

        public boolean isSellSignal() {
            return this == SELL || this == STRONG_SELL || this == STOP_LOSS || this == TAKE_PROFIT;
        }
    }
}


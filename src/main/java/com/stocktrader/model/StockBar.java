package com.stocktrader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股票K线数据（OHLCV）
 * 支持日线、周线、月线、分钟线
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockBar {

    /** 股票代码 */
    private String stockCode;

    /** 股票名称（新浪实时行情解析时填充） */
    private String stockName;

    /** 交易时间（日线用日期，分钟线用完整时间） */
    private LocalDateTime dateTime;

    /** 开盘价 */
    private double open;

    /** 最高价 */
    private double high;

    /** 最低价 */
    private double low;

    /** 收盘价 */
    private double close;

    /** 成交量（手） */
    private long volume;

    /** 成交额（元） */
    private double amount;

    /** 涨跌幅（%） */
    private double changePercent;

    /** 涨跌额 */
    private double change;

    /** 换手率（%） */
    private double turnoverRate;

    /** K线周期类型 */
    private BarPeriod period;

    /** 复权类型 */
    private AdjustType adjustType;

    public LocalDate getDate() {
        return dateTime != null ? dateTime.toLocalDate() : null;
    }

    public enum BarPeriod {
        MIN_1("1分钟"),
        MIN_5("5分钟"),
        MIN_15("15分钟"),
        MIN_30("30分钟"),
        MIN_60("60分钟"),
        DAILY("日线"),
        WEEKLY("周线"),
        MONTHLY("月线");

        private final String description;

        BarPeriod(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public enum AdjustType {
        NONE("不复权"),
        FORWARD("前复权"),
        BACKWARD("后复权");

        private final String description;

        AdjustType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}


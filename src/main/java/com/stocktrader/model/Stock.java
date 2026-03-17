package com.stocktrader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 股票基本信息模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Stock {

    /** 股票代码，如 600519、000001 */
    private String code;

    /** 股票名称，如 贵州茅台、平安银行 */
    private String name;

    /** 交易所：SH（上交所）、SZ（深交所）、BJ（北交所） */
    private String exchange;

    /** 行业分类 */
    private String industry;

    /** 总市值（元） */
    private Double totalMarketCap;

    /** 流通市值（元） */
    private Double circulatingMarketCap;

    /** 市盈率(PE) */
    private Double pe;

    /** 市净率(PB) */
    private Double pb;

    /** 52周最高价 */
    private Double week52High;

    /** 52周最低价 */
    private Double week52Low;

    /** 是否为ST股票 */
    private Boolean isSt;

    /** 股票状态：NORMAL-正常, SUSPENDED-停牌, DELISTED-退市 */
    private StockStatus status;

    /**
     * 获取完整的同花顺格式股票代码
     * 例如：sh600519、sz000001
     */
    public String getFullCode() {
        if (exchange == null || code == null) {
            return code;
        }
        return exchange.toLowerCase() + code;
    }

    public enum StockStatus {
        NORMAL("正常"),
        SUSPENDED("停牌"),
        DELISTED("退市");

        private final String description;

        StockStatus(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}


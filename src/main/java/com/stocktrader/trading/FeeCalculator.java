package com.stocktrader.trading;

/**
 * A股交易费用计算器
 * <p>
 * 费用明细（2024年标准）：
 * - 佣金：万分之2.5（最低5元），买卖双向收取
 * - 印花税：千分之1，仅卖出时收取（2023年8月降至0.05%）
 * - 过户费：沪市千分之0.02，深市免收
 * - 证管费：万分之0.2（包含在交易所费用内）
 */
public class FeeCalculator {

    /** 佣金率（默认万分之2.5） */
    private final double commissionRate;

    /** 最低佣金（元） */
    private final double minCommission;

    /** 印花税率（卖出时收取） */
    private final double stampTaxRate;

    /** 过户费率（沪市收取） */
    private final double transferFeeRate;

    /**
     * 默认构造，使用标准费率
     */
    public FeeCalculator() {
        this(0.00025, 5.0, 0.0005, 0.00002);
    }

    /**
     * 自定义费率构造
     */
    public FeeCalculator(double commissionRate, double minCommission,
                         double stampTaxRate, double transferFeeRate) {
        this.commissionRate = commissionRate;
        this.minCommission = minCommission;
        this.stampTaxRate = stampTaxRate;
        this.transferFeeRate = transferFeeRate;
    }

    /**
     * 计算买入费用
     * @param amount 交易金额
     * @param exchange 交易所（SH/SZ）
     * @return 费用明细
     */
    public FeeDetail calculateBuyFee(double amount, String exchange) {
        double commission = Math.max(amount * commissionRate, minCommission);
        double transferFee = "SH".equals(exchange) ? amount * transferFeeRate : 0;
        double total = commission + transferFee;
        return new FeeDetail(commission, 0, transferFee, total);
    }

    /**
     * 计算卖出费用（包含印花税）
     * @param amount 交易金额
     * @param exchange 交易所（SH/SZ）
     * @return 费用明细
     */
    public FeeDetail calculateSellFee(double amount, String exchange) {
        double commission = Math.max(amount * commissionRate, minCommission);
        double stampTax = amount * stampTaxRate;
        double transferFee = "SH".equals(exchange) ? amount * transferFeeRate : 0;
        double total = commission + stampTax + transferFee;
        return new FeeDetail(commission, stampTax, transferFee, total);
    }

    /**
     * 快速计算买入总费用
     */
    public double buyFeeTotal(double amount, String exchange) {
        return calculateBuyFee(amount, exchange).total;
    }

    /**
     * 快速计算卖出总费用
     */
    public double sellFeeTotal(double amount, String exchange) {
        return calculateSellFee(amount, exchange).total;
    }

    /**
     * 费用明细记录
     */
    public static class FeeDetail {
        public final double commission;   // 佣金
        public final double stampTax;     // 印花税
        public final double transferFee;  // 过户费
        public final double total;        // 合计

        public FeeDetail(double commission, double stampTax, double transferFee, double total) {
            this.commission = commission;
            this.stampTax = stampTax;
            this.transferFee = transferFee;
            this.total = total;
        }

        @Override
        public String toString() {
            return String.format("佣金=%.2f 印花税=%.2f 过户费=%.4f 合计=%.2f",
                    commission, stampTax, transferFee, total);
        }
    }
}


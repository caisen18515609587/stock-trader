package com.stocktrader.trading;

/**
 * 多市场交易费用计算器（A股 + 港股）
 *
 * <p><b>A股费率（2024年标准）：</b>
 * <ul>
 *   <li>佣金：万分之2.5（最低5元），买卖双向收取</li>
 *   <li>印花税：0.05%（千分之0.5），仅卖出时收取</li>
 *   <li>过户费：沪市0.002%，深市免收</li>
 * </ul>
 *
 * <p><b>港股费率（2024年香港交易所标准）：</b>
 * <ul>
 *   <li>佣金：万分之2.5（最低5港元），买卖双向收取（券商参考值）</li>
 *   <li>印花税：0.1%，买卖双向收取（香港政府税，按四舍五入至整数港元）</li>
 *   <li>交易征费（SFC Levy）：0.0027%，买卖双向</li>
 *   <li>交易系统使用费（Tariff）：每笔0.50港元，买卖双向</li>
 *   <li>交易所交易费：0.005%，买卖双向</li>
 * </ul>
 */
public class FeeCalculator {

    // ===== A 股费率 =====
    /** A股佣金率（默认万分之2.5） */
    private final double commissionRate;
    /** A股最低佣金（元） */
    private final double minCommission;
    /** A股印花税率（仅卖出，0.05%） */
    private final double stampTaxRate;
    /** A股过户费率（沪市，0.002%） */
    private final double transferFeeRate;

    // ===== 港股费率常量 =====
    /** 港股印花税率：0.1%，买卖双向（香港政府税） */
    private static final double HK_STAMP_TAX_RATE        = 0.001;
    /** 港股交易征费（SFC Levy）：0.0027% */
    private static final double HK_SFC_LEVY_RATE         = 0.000027;
    /** 港股交易所交易费：0.005% */
    private static final double HK_EXCHANGE_FEE_RATE     = 0.00005;
    /** 港股交易系统使用费（每笔固定 0.50 港元） */
    private static final double HK_TARIFF_PER_ORDER      = 0.50;
    /** 港股佣金率（参考值，万分之2.5，最低5港元） */
    private static final double HK_COMMISSION_RATE       = 0.00025;
    private static final double HK_MIN_COMMISSION        = 5.0;

    /**
     * 默认构造，使用A股标准费率
     */
    public FeeCalculator() {
        this(0.00025, 5.0, 0.0005, 0.00002);
    }

    /**
     * 自定义A股费率构造
     */
    public FeeCalculator(double commissionRate, double minCommission,
                         double stampTaxRate, double transferFeeRate) {
        this.commissionRate  = commissionRate;
        this.minCommission   = minCommission;
        this.stampTaxRate    = stampTaxRate;
        this.transferFeeRate = transferFeeRate;
    }

    /**
     * 计算买入费用
     * @param amount   交易金额（人民币/港元）
     * @param exchange 交易所（SH/SZ/BJ/HK）
     * @return 费用明细
     */
    public FeeDetail calculateBuyFee(double amount, String exchange) {
        if ("HK".equals(exchange)) {
            return calculateHkFee(amount, true);
        }
        double commission  = Math.max(amount * commissionRate, minCommission);
        double transferFee = "SH".equals(exchange) ? amount * transferFeeRate : 0;
        double total       = commission + transferFee;
        return new FeeDetail(commission, 0, transferFee, total);
    }

    /**
     * 计算卖出费用（A股含印花税，港股印花税买卖双向）
     * @param amount   交易金额（人民币/港元）
     * @param exchange 交易所（SH/SZ/BJ/HK）
     * @return 费用明细
     */
    public FeeDetail calculateSellFee(double amount, String exchange) {
        if ("HK".equals(exchange)) {
            return calculateHkFee(amount, false);
        }
        double commission  = Math.max(amount * commissionRate, minCommission);
        double stampTax    = amount * stampTaxRate;
        double transferFee = "SH".equals(exchange) ? amount * transferFeeRate : 0;
        double total       = commission + stampTax + transferFee;
        return new FeeDetail(commission, stampTax, transferFee, total);
    }

    /**
     * 计算港股交易费用（买卖对称）
     * <p>港股各项费用：
     * <ul>
     *   <li>佣金：max(金额×0.025%, 5港元)</li>
     *   <li>印花税：金额×0.1%，向上取整至整数港元（买卖双向）</li>
     *   <li>交易征费（SFC Levy）：金额×0.0027%</li>
     *   <li>交易所交易费：金额×0.005%</li>
     *   <li>交易系统使用费：固定0.50港元/笔</li>
     * </ul>
     * @param amount  交易金额（港元）
     * @param isBuy   是否买入（港股印花税买卖双向，所以 isBuy 对总费用无影响，仅为语义清晰保留）
     * @return 费用明细
     */
    private FeeDetail calculateHkFee(double amount, boolean isBuy) {
        double commission  = Math.max(amount * HK_COMMISSION_RATE, HK_MIN_COMMISSION);
        // 印花税：向上取整至整数港元（香港规则）
        double stampTax    = Math.ceil(amount * HK_STAMP_TAX_RATE);
        double sfcLevy     = amount * HK_SFC_LEVY_RATE;
        double exchangeFee = amount * HK_EXCHANGE_FEE_RATE;
        double tariff      = HK_TARIFF_PER_ORDER;
        // transferFee 字段复用存放港股"交易征费+交易所费+系统使用费"小项合计
        double otherFees   = sfcLevy + exchangeFee + tariff;
        double total       = commission + stampTax + otherFees;
        return new FeeDetail(commission, stampTax, otherFees, total);
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
        public final double stampTax;     // 印花税（港股买卖双向）
        public final double transferFee;  // 过户费（A股沪市）/ 港股其他杂费合计
        public final double total;        // 合计

        public FeeDetail(double commission, double stampTax, double transferFee, double total) {
            this.commission  = commission;
            this.stampTax    = stampTax;
            this.transferFee = transferFee;
            this.total       = total;
        }

        @Override
        public String toString() {
            return String.format("佣金%.2f + 印花税%.2f + 过户/杂费%.4f = 合计%.2f",
                    commission, stampTax, transferFee, total);
        }
    }
}


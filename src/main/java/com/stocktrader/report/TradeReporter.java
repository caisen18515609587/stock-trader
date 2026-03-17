package com.stocktrader.report;

import com.stocktrader.model.Order;
import com.stocktrader.model.Portfolio;
import com.stocktrader.trading.BacktestEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 交易报告生成器
 * <p>
 * 功能：
 * 1. 生成账户汇总报告
 * 2. 生成交易明细报告（CSV格式）
 * 3. 生成策略绩效分析报告
 * 4. 生成经验总结建议
 */
public class TradeReporter {

    private static final Logger log = LoggerFactory.getLogger(TradeReporter.class);
    private static final DateTimeFormatter DT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * 生成完整账户报告（控制台输出）
     */
    public void printAccountReport(Portfolio portfolio) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.printf("║       账户报告 - %s%-34s║%n",
                portfolio.getAccountName(), " ");
        System.out.printf("║       生成时间: %-42s║%n",
                LocalDateTime.now().format(DT_FORMAT));
        System.out.println("╠══════════════════════════════════════════════════════════╣");

        // 资产概览
        System.out.println("║ 【资产概览】                                              ║");
        System.out.printf("║   初始资金:    %,-20.2f                     ║%n", portfolio.getInitialCapital());
        System.out.printf("║   可用资金:    %,-20.2f                     ║%n", portfolio.getAvailableCash());
        System.out.printf("║   持仓市值:    %,-20.2f                     ║%n", portfolio.getTotalPositionValue());
        System.out.printf("║   账户总资产:  %,-20.2f                     ║%n", portfolio.getTotalAssets());
        System.out.printf("║   总收益:      %,-20.2f (%.2f%%)             ║%n",
                portfolio.getTotalProfit(), portfolio.getTotalProfitRate());
        System.out.println("╠══════════════════════════════════════════════════════════╣");

        // 持仓明细
        if (!portfolio.getPositions().isEmpty()) {
            System.out.println("║ 【当前持仓】                                              ║");
            portfolio.getPositions().forEach((code, pos) -> {
                System.out.printf("║   %-8s %-6s 持%5d股 成本%8.2f 现%8.2f 盈%+.1f%%%n",
                        code, pos.getStockName(), pos.getQuantity(),
                        pos.getAvgCost(), pos.getCurrentPrice(),
                        pos.calculateProfitRate());
            });
            System.out.println("╠══════════════════════════════════════════════════════════╣");
        }

        // 交易统计
        List<Order> orders = portfolio.getOrderHistory();
        if (!orders.isEmpty()) {
            TradeStats stats = calculateTradeStats(orders);
            System.out.println("║ 【交易统计】                                              ║");
            System.out.printf("║   总交易次数:  %-44d║%n", stats.totalTrades);
            System.out.printf("║   买入次数:    %-44d║%n", stats.buyTrades);
            System.out.printf("║   卖出次数:    %-44d║%n", stats.sellTrades);
            System.out.printf("║   已实现盈亏:  %-44.2f║%n", stats.realizedPnl);
            System.out.printf("║   胜率:        %-44s║%n", String.format("%.1f%% (%d/%d)", stats.winRate, stats.winCount, stats.sellTrades));
            System.out.printf("║   总手续费:    %-44.2f║%n", stats.totalFee);
        }

        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    /**
     * 生成交易明细CSV报告
     * @param orders 订单列表
     * @param filePath 文件路径
     */
    public void exportTradesToCsv(List<Order> orders, String filePath) throws IOException {
        try (PrintWriter pw = new PrintWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(filePath), java.nio.charset.StandardCharsets.UTF_8))) {
            // BOM for Excel
            pw.write('\uFEFF');
            pw.println("订单ID,股票代码,股票名称,操作类型,状态,委托价格,成交价格,数量,金额,佣金,印花税,过户费,总费用,策略,时间,备注");

            for (Order order : orders) {
                pw.printf("%s,%s,%s,%s,%s,%.2f,%.2f,%d,%.2f,%.2f,%.2f,%.4f,%.2f,%s,%s,%s%n",
                        order.getOrderId(),
                        order.getStockCode(),
                        order.getStockName() != null ? order.getStockName() : "",
                        order.getOrderType().getDescription(),
                        order.getStatus().getDescription(),
                        order.getPrice(),
                        order.getFilledPrice(),
                        order.getFilledQuantity(),
                        order.getAmount(),
                        order.getCommission(),
                        order.getStampTax(),
                        order.getTransferFee(),
                        order.getTotalFee(),
                        order.getStrategyName() != null ? order.getStrategyName() : "",
                        order.getFilledTime() != null ? order.getFilledTime().format(DT_FORMAT) : "",
                        order.getRemark() != null ? order.getRemark().replace(",", "；") : ""
                );
            }
        }
        log.info("交易明细已导出到: {}", filePath);
    }

    /**
     * 生成回测结果报告
     */
    public void printBacktestReport(BacktestEngine.BacktestResult result) {
        if (result == null) {
            System.out.println("回测结果为空");
            return;
        }
        result.print();
        printExperienceSummary(result);
    }

    /**
     * 根据回测结果生成经验总结和改进建议
     */
    public void printExperienceSummary(BacktestEngine.BacktestResult result) {
        System.out.println("\n【经验总结与优化建议】");
        System.out.println("--------------------------------------------------");

        List<String> suggestions = new ArrayList<>();
        List<String> strengths = new ArrayList<>();

        // 收益率分析
        if (result.totalReturn > result.benchmarkReturn + 5) {
            strengths.add(String.format("✅ 策略跑赢基准 %.1f%%，超额收益显著",
                    result.totalReturn - result.benchmarkReturn));
        } else if (result.totalReturn < result.benchmarkReturn) {
            suggestions.add(String.format("⚠️ 策略跑输基准 %.1f%%，建议审查信号逻辑",
                    result.benchmarkReturn - result.totalReturn));
        }

        // 最大回撤分析
        if (result.maxDrawdown > 20) {
            suggestions.add(String.format("⚠️ 最大回撤 %.1f%% 过大（建议控制在15%%内），需加强止损策略", result.maxDrawdown));
        } else if (result.maxDrawdown < 10) {
            strengths.add(String.format("✅ 最大回撤 %.1f%% 控制良好，风险管理有效", result.maxDrawdown));
        }

        // 胜率分析
        if (result.winRate > 60) {
            strengths.add(String.format("✅ 胜率 %.1f%% 较高，信号质量不错", result.winRate));
        } else if (result.winRate < 40) {
            suggestions.add(String.format("⚠️ 胜率仅 %.1f%%，建议提高买入条件门槛或增加过滤条件", result.winRate));
        }

        // 盈亏比分析
        if (result.profitLossRatio >= 2.0) {
            strengths.add(String.format("✅ 盈亏比 %.2f，盈利是亏损的2倍以上，策略效果良好", result.profitLossRatio));
        } else if (result.profitLossRatio < 1.0) {
            suggestions.add(String.format("⚠️ 盈亏比 %.2f 偏低，单次亏损大于盈利，建议提前止盈或收紧止损", result.profitLossRatio));
        }

        // 夏普比率分析
        if (result.sharpeRatio > 1.5) {
            strengths.add(String.format("✅ 夏普比率 %.2f > 1.5，风险调整后收益优秀", result.sharpeRatio));
        } else if (result.sharpeRatio < 0.5) {
            suggestions.add(String.format("⚠️ 夏普比率 %.2f 偏低，收益未能有效补偿风险", result.sharpeRatio));
        }

        // 交易频率
        if (result.totalTrades == 0) {
            suggestions.add("⚠️ 回测期内无交易，信号条件可能过于严格，建议放宽入场条件");
        } else if (result.totalTrades > 100) {
            suggestions.add(String.format("⚠️ 交易频率过高（%d次），手续费损耗大（总计%.2f元），考虑降低交易频率",
                    result.totalTrades, result.totalFee));
        }

        // 手续费影响
        double feeImpact = result.totalFee / result.initialCapital * 100;
        if (feeImpact > 2) {
            suggestions.add(String.format("⚠️ 手续费占初始资金 %.2f%%，影响较大，建议降低交易频率", feeImpact));
        }

        // 输出优点
        if (!strengths.isEmpty()) {
            System.out.println("策略优势：");
            strengths.forEach(s -> System.out.println("  " + s));
        }

        // 输出建议
        if (!suggestions.isEmpty()) {
            System.out.println("改进建议：");
            suggestions.forEach(s -> System.out.println("  " + s));
        }

        // 通用建议
        System.out.println("\n通用风险提示：");
        System.out.println("  📌 历史回测结果不代表未来表现，实盘交易请谨慎");
        System.out.println("  📌 建议先以小额资金（如10万元）进行模拟，积累足够经验后再增加资金");
        System.out.println("  📌 任何时候单只股票仓位不超过总资金30%，做好分散投资");
        System.out.println("  📌 严格遵守止损纪律，避免持有亏损股票不止损");
        System.out.println("  📌 重大消息面变化时，技术分析可能失效，需结合基本面判断");
    }

    /**
     * 生成多策略对比报告
     */
    public void printStrategyComparison(List<BacktestEngine.BacktestResult> results) {
        if (results == null || results.isEmpty()) return;

        System.out.println("\n【策略对比报告】");
        System.out.printf("%-18s %8s %8s %8s %6s %6s%n",
                "策略名称", "收益率", "超额收益", "最大回撤", "胜率", "夏普");
        System.out.println("-----------------------------------------------------------------");

        results.stream()
                .sorted(Comparator.comparingDouble(r -> -r.totalReturn))
                .forEach(r -> System.out.printf("%-18s %7.2f%% %7.2f%% %7.2f%% %5.1f%% %5.2f%n",
                        r.strategyName,
                        r.totalReturn,
                        r.totalReturn - r.benchmarkReturn,
                        r.maxDrawdown,
                        r.winRate,
                        r.sharpeRatio));

        // 找出最优策略
        BacktestEngine.BacktestResult best = results.stream()
                .max(Comparator.comparingDouble(r -> r.totalReturn))
                .orElse(null);
        if (best != null) {
            System.out.printf("%n推荐策略：%s（收益率%.2f%%，夏普%.2f）%n",
                    best.strategyName, best.totalReturn, best.sharpeRatio);
        }
    }

    // ===== 内部统计工具 =====

    private TradeStats calculateTradeStats(List<Order> orders) {
        int buyTrades = 0, sellTrades = 0, winCount = 0;
        double realizedPnl = 0, totalFee = 0;
        // [Bug Fix] 使用 (totalCost, totalQty) 双字段跟踪均价，支持多次加仓后的加权平均成本
        // 原先用 put(code, filledPrice) 会在每次加仓时直接覆盖均价，导致盈亏计算错误
        Map<String, double[]> posMap = new HashMap<>(); // code -> [totalCost, totalQty]
        List<Double> pnls = new ArrayList<>();

        for (Order order : orders) {
            totalFee += order.getTotalFee();
            if (order.getOrderType() == Order.OrderType.BUY) {
                buyTrades++;
                double[] pos = posMap.getOrDefault(order.getStockCode(), new double[]{0, 0});
                pos[0] += order.getFilledPrice() * order.getFilledQuantity(); // 累计总成本
                pos[1] += order.getFilledQuantity();                          // 累计总股数
                posMap.put(order.getStockCode(), pos);
            } else {
                sellTrades++;
                double[] pos = posMap.get(order.getStockCode());
                if (pos != null && pos[1] > 0) {
                    double avgCost = pos[0] / pos[1]; // 加权平均成本
                    double pnl = (order.getFilledPrice() - avgCost) * order.getFilledQuantity() - order.getTotalFee();
                    realizedPnl += pnl;
                    pnls.add(pnl);
                    if (pnl > 0) winCount++;
                    // 卖出后减少持仓数量和对应成本（按比例扣减）
                    int sellQty = order.getFilledQuantity();
                    if (sellQty >= pos[1]) {
                        posMap.remove(order.getStockCode()); // 全部卖出，清除记录
                    } else {
                        pos[0] -= avgCost * sellQty; // 按均价扣减已卖出部分成本
                        pos[1] -= sellQty;
                    }
                }
            }
        }

        double winRate = sellTrades > 0 ? (double) winCount / sellTrades * 100 : 0;
        return new TradeStats(buyTrades + sellTrades, buyTrades, sellTrades,
                realizedPnl, totalFee, winRate, winCount);
    }

    private static class TradeStats {
        final int totalTrades, buyTrades, sellTrades, winCount;
        final double realizedPnl, totalFee, winRate;

        TradeStats(int totalTrades, int buyTrades, int sellTrades,
                   double realizedPnl, double totalFee, double winRate, int winCount) {
            this.totalTrades = totalTrades;
            this.buyTrades = buyTrades;
            this.sellTrades = sellTrades;
            this.realizedPnl = realizedPnl;
            this.totalFee = totalFee;
            this.winRate = winRate;
            this.winCount = winCount;
        }
    }
}


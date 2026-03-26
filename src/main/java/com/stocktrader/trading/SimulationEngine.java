package com.stocktrader.trading;

import com.stocktrader.analysis.StockAnalyzer;
import com.stocktrader.datasource.StockDataProvider;
import com.stocktrader.model.*;
import com.stocktrader.strategy.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 模拟交易引擎
 * <p>
 * 支持两种模式：
 * 1. 实时模拟：对接真实行情，按指定金额进行模拟操作，不实际下单
 * 2. 历史回测：使用历史K线数据，模拟在指定时间段内的交易结果
 */
public class SimulationEngine {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    private final StockDataProvider dataProvider;
    private final StockAnalyzer stockAnalyzer;
    private final FeeCalculator feeCalculator;
    private final List<TradingStrategy> strategies;

    // 每次操作的最小买入金额（元）
    private static final double MIN_BUY_AMOUNT = 1000;
    // 每次买入最大金额（元），防止过于集中
    private static final double MAX_SINGLE_BUY = 500000;

    public SimulationEngine(StockDataProvider dataProvider) {
        this.dataProvider = dataProvider;
        this.stockAnalyzer = new StockAnalyzer();
        this.feeCalculator = new FeeCalculator();
        this.strategies = new ArrayList<>();
    }

    /**
     * 添加交易策略
     */
    public void addStrategy(TradingStrategy strategy) {
        strategies.add(strategy);
        log.info("已添加策略：{}", strategy.getStrategyName());
    }

    /**
     * 对指定股票列表进行实时模拟分析
     * @param portfolio 模拟账户
     * @param stockCodes 要监控的股票列表
     * @return 本次产生的所有信号
     */
    public List<TradeSignal> runSimulation(Portfolio portfolio, List<String> stockCodes) {
        List<TradeSignal> allSignals = new ArrayList<>();
        log.info("开始模拟交易扫描，监控{}只股票", stockCodes.size());

        for (String code : stockCodes) {
            try {
                // 获取历史K线（用于技术分析）
                java.time.LocalDate endDate = java.time.LocalDate.now();
                java.time.LocalDate startDate = endDate.minusYears(1);
                List<StockBar> bars = dataProvider.getDailyBars(code, startDate, endDate,
                        StockBar.AdjustType.FORWARD);

                if (bars == null || bars.size() < 60) {
                    log.debug("股票{}数据不足，跳过", code);
                    continue;
                }

                // 获取实时行情，更新最新价格
                StockBar latestBar = dataProvider.getRealTimeQuote(code);
                if (latestBar != null) {
                    // 更新或追加最新K线
                    if (!bars.isEmpty() &&
                            bars.get(bars.size() - 1).getDate().equals(latestBar.getDate())) {
                        bars.set(bars.size() - 1, latestBar);
                    } else {
                        bars.add(latestBar);
                    }
                    // 更新持仓现价
                    portfolio.updatePositionPrice(code, latestBar.getClose());
                }

                // 技术分析
                Stock stockInfo = dataProvider.getStockInfo(code);
                String stockName = stockInfo != null ? stockInfo.getName() : code;
                AnalysisResult analysis = stockAnalyzer.analyze(code, stockName, bars);

                // 遍历所有策略生成信号
                for (TradingStrategy strategy : strategies) {
                    TradeSignal signal = strategy.generateSignal(code, stockName, bars, analysis, portfolio);

                    if (signal.getSignalType() != TradeSignal.SignalType.HOLD) {
                        allSignals.add(signal);
                        log.info("信号：{} {} {} 强度:{} 原因:{}",
                                signal.getStrategyName(), code, signal.getSignalType().getDescription(),
                                signal.getStrength(), signal.getReason());

                        // 自动执行信号
                        executeSignal(signal, portfolio, strategy);
                    }
                }

            } catch (Exception e) {
                log.error("处理股票{}时发生错误", code, e);
            }
        }

        log.info("扫描完成，产生{}个信号，账户总资产: {}，总收益率: {}%",
                allSignals.size(),
                String.format("%.2f", portfolio.getTotalAssets()),
                String.format("%.2f", portfolio.getTotalProfitRate()));
        return allSignals;
    }

    /**
     * 执行交易信号（模拟下单）
     */
    public boolean executeSignal(TradeSignal signal, Portfolio portfolio, TradingStrategy strategy) {
        if (signal.getSignalType().isBuySignal()) {
            return executeBuy(signal, portfolio, strategy);
        } else if (signal.getSignalType().isSellSignal()) {
            return executeSell(signal, portfolio);
        }
        return false;
    }

    /**
     * 模拟买入
     */
    private boolean executeBuy(TradeSignal signal, Portfolio portfolio, TradingStrategy strategy) {
        double availableCash = portfolio.getAvailableCash();
        if (availableCash < MIN_BUY_AMOUNT) {
            log.warn("可用资金不足({})，无法买入{}",
                    String.format("%.2f", availableCash), signal.getStockCode());
            return false;
        }

        // 计算买入金额
        double positionRatio = signal.getPositionRatio() > 0 ?
                signal.getPositionRatio() :
                strategy.calculatePositionSize(signal, portfolio);
        double buyAmount = Math.min(availableCash * positionRatio, MAX_SINGLE_BUY);
        buyAmount = Math.max(buyAmount, MIN_BUY_AMOUNT);

        // 获取当前价格
        double price = signal.getSuggestedPrice();
        if (price <= 0) return false;

        // 计算买入数量（A股100股为一手，必须是100的整数倍）
        int quantity = (int) (buyAmount / price / 100) * 100;
        if (quantity <= 0) {
            log.warn("买入数量为0，买入金额{}，股价{}",
                    String.format("%.2f", buyAmount), String.format("%.2f", price));
            return false;
        }

        double actualAmount = quantity * price;
        String exchange = detectExchange(signal.getStockCode());
        FeeCalculator.FeeDetail fee = feeCalculator.calculateBuyFee(actualAmount, exchange);

        // 创建订单
        Order order = Order.builder()
                .orderId(UUID.randomUUID().toString())
                .stockCode(signal.getStockCode())
                .stockName(signal.getStockName())
                .orderType(Order.OrderType.BUY)
                .status(Order.OrderStatus.FILLED)
                .price(price)
                .quantity(quantity)
                .filledPrice(price)
                .filledQuantity(quantity)
                .amount(actualAmount)
                .commission(fee.commission)
                .stampTax(fee.stampTax)
                .transferFee(fee.transferFee)
                .totalFee(fee.total)
                .createTime(LocalDateTime.now())
                .filledTime(LocalDateTime.now())
                .strategyName(signal.getStrategyName())
                .remark(signal.getReason())
                .build();

        boolean success = portfolio.executeBuy(order);
        if (success) {
            log.info("模拟买入成功：{} {}股 @{}，金额{}，费用{}",
                    signal.getStockCode(), quantity,
                    String.format("%.2f", price),
                    String.format("%.2f", actualAmount), fee);
        }
        return success;
    }

    /**
     * 模拟卖出
     */
    private boolean executeSell(TradeSignal signal, Portfolio portfolio) {
        Position position = portfolio.getPosition(signal.getStockCode());
        if (position == null || position.getAvailableQuantity() <= 0) {
            log.warn("无持仓或持仓不可用：{}", signal.getStockCode());
            return false;
        }

        double price = signal.getSuggestedPrice();
        int quantity = position.getAvailableQuantity();

        // 部分卖出：止盈时卖出一半，止损时全部卖出
        if (signal.getSignalType() == TradeSignal.SignalType.TAKE_PROFIT) {
            quantity = Math.max(quantity / 2, 100);
            quantity = (quantity / 100) * 100;
        }

        // 提前保存成本价（executeSell 后持仓可能被清除，avgCost 会变为 0）
        double avgCost = position.getAvgCost();

        double actualAmount = quantity * price;
        String exchange = detectExchange(signal.getStockCode());
        FeeCalculator.FeeDetail fee = feeCalculator.calculateSellFee(actualAmount, exchange);

        Order order = Order.builder()
                .orderId(UUID.randomUUID().toString())
                .stockCode(signal.getStockCode())
                .stockName(signal.getStockName())
                .orderType(Order.OrderType.SELL)
                .status(Order.OrderStatus.FILLED)
                .price(price)
                .quantity(quantity)
                .filledPrice(price)
                .filledQuantity(quantity)
                .amount(actualAmount)
                .commission(fee.commission)
                .stampTax(fee.stampTax)
                .transferFee(fee.transferFee)
                .totalFee(fee.total)
                .createTime(LocalDateTime.now())
                .filledTime(LocalDateTime.now())
                .strategyName(signal.getStrategyName())
                .remark(signal.getReason())
                .build();

        boolean success = portfolio.executeSell(order);
        if (success) {
            double profitLoss = (price - avgCost) * quantity - fee.total;
            log.info("模拟卖出成功：{} {}股 @{}，盈亏{}，费用{}",
                    signal.getStockCode(), quantity,
                    String.format("%.2f", price),
                    String.format("%.2f", profitLoss), fee);
        }
        return success;
    }

    /**
     * 根据代码判断交易所
     */
    private String detectExchange(String code) {
        if (code == null) return "SZ";
        char first = code.charAt(0);
        return first == '6' ? "SH" : "SZ";
    }

    /**
     * 打印账户状态摘要
     */
    public void printPortfolioSummary(Portfolio portfolio) {
        String sep60 = "============================================================";
        String sep60d = "------------------------------------------------------------";
        System.out.println("\n" + sep60);
        System.out.printf("账户：%s (%s)%n", portfolio.getAccountName(), portfolio.getMode().getDescription());
        System.out.printf("初始资金：    %,.2f 元%n", portfolio.getInitialCapital());
        System.out.printf("可用资金：    %,.2f 元%n", portfolio.getAvailableCash());
        System.out.printf("持仓市值：    %,.2f 元%n", portfolio.getTotalPositionValue());
        System.out.printf("总资产：      %,.2f 元%n", portfolio.getTotalAssets());
        System.out.printf("总收益：      %,.2f 元 (%.2f%%)%n",
                portfolio.getTotalProfit(), portfolio.getTotalProfitRate());
        System.out.println(sep60d);

        if (!portfolio.getPositions().isEmpty()) {
            System.out.println("当前持仓：");
            System.out.printf("  %-10s %-8s %8s %10s %10s %10s%n",
                    "代码", "名称", "数量", "成本", "现价", "盈亏%");
            portfolio.getPositions().forEach((code, pos) ->
                    System.out.printf("  %-10s %-8s %8d %10.2f %10.2f %9.2f%%%n",
                            code, pos.getStockName(), pos.getQuantity(),
                            pos.getAvgCost(), pos.getCurrentPrice(),
                            pos.calculateProfitRate())
            );
        } else {
            System.out.println("当前无持仓");
        }
        System.out.println(sep60);
    }
}


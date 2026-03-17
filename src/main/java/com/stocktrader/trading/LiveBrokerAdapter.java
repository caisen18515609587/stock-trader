package com.stocktrader.trading;

import com.stocktrader.model.Order;
import com.stocktrader.model.Portfolio;
import com.stocktrader.model.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 实盘 Broker 适配器
 * <p>
 * 职责：
 * 1. 将 AutoTrader 产生的买/卖请求路由到 QMT 实盘下单（通过 {@link QmtBrokerClient}）
 * 2. 等待委托终态（通过 {@link LiveOrderTracker}），将实盘成交结果写回模拟 {@link Portfolio}
 * 3. 定期（每分钟）从实盘同步持仓和资金到 Portfolio，保证模拟账户与实盘一致
 * 4. QMT 不可用时自动降级为纯模拟模式（行为与原有逻辑完全一致）
 * <p>
 * 与 AutoTrader 的集成方式：
 * <pre>
 *   // AutoTrader 构建时传入
 *   LiveBrokerAdapter liveAdapter = new LiveBrokerAdapter(portfolio, feeCalculator, "http://localhost:8098");
 *   autoTrader.setLiveBrokerAdapter(liveAdapter);
 *
 *   // AutoTrader.executeBuy() 中：
 *   if (liveBrokerAdapter != null) {
 *       liveBrokerAdapter.submitBuy(code, name, price, quantity, fee, signal, analysis, ...);
 *   } else {
 *       portfolio.executeBuy(order);  // 纯模拟
 *   }
 * </pre>
 */
public class LiveBrokerAdapter {

    private static final Logger log = LoggerFactory.getLogger(LiveBrokerAdapter.class);

    /** 实盘委托等待超时（秒）：买卖信号触发后最多等待此时长 */
    private static final int LIVE_ORDER_TIMEOUT_SEC = 60;

    private final QmtBrokerClient  brokerClient;
    private final LiveOrderTracker orderTracker;
    private final FeeCalculator    feeCalculator;

    /** 上次从实盘同步持仓的时间戳（毫秒），用于控制同步频率 */
    private volatile long lastSyncMs = 0L;
    /** 实盘持仓同步间隔（60 秒）*/
    private static final long SYNC_INTERVAL_MS = 60_000L;

    /**
     * @param feeCalculator  费用计算器（与 AutoTrader 共用同一实例）
     * @param bridgeUrl      qmt_bridge.py HTTP 地址，默认 "http://localhost:8098"
     */
    public LiveBrokerAdapter(FeeCalculator feeCalculator, String bridgeUrl) {
        this.feeCalculator = feeCalculator;
        this.brokerClient  = new QmtBrokerClient(bridgeUrl);
        this.orderTracker  = new LiveOrderTracker(brokerClient);
    }

    public LiveBrokerAdapter(FeeCalculator feeCalculator) {
        this(feeCalculator, "http://localhost:8098");
    }

    // =====================================================================
    //  可用性
    // =====================================================================

    /**
     * QMT 实盘是否可用（qmt_bridge.py 已启动且连接了 MiniQMT）
     * AutoTrader 每次下单前调用此方法，不可用则降级为模拟。
     */
    public boolean isLiveAvailable() {
        return brokerClient.isAvailable();
    }

    // =====================================================================
    //  买入
    // =====================================================================

    /**
     * 提交实盘买入委托，同步等待成交并将结果写回 Portfolio。
     *
     * @param portfolio     当前账户（成交后写入持仓、扣减资金）
     * @param code          股票代码
     * @param stockName     股票名称
     * @param price         委托价格
     * @param quantity      委托数量（股，100的倍数）
     * @param strategyName  策略名（传给 QMT 订单备注）
     * @param reason        信号原因（传给 QMT 订单备注 + Portfolio Order.remark）
     * @return 成交后的 Order 对象；下单失败或未成交返回 null
     */
    public Order submitBuyLive(Portfolio portfolio,
                                String code, String stockName,
                                double price, int quantity,
                                String strategyName, String reason) {
        log.info("[实盘买入] {} {} {}股 @{}", code, stockName, quantity, String.format("%.2f", price));

        // 1. 下单
        String remark = (strategyName != null ? strategyName : "") +
                (reason != null ? " | " + reason : "");
        String orderId = brokerClient.placeBuyOrder(code, price, quantity, remark);
        if (orderId == null) {
            log.error("[实盘买入] {} 下单失败，QMT 返回 null orderId", code);
            return null;
        }

        // 2. 同步等待委托终态
        QmtBrokerClient.OrderStatusResult result =
                orderTracker.waitForCompletion(orderId, code, "BUY", LIVE_ORDER_TIMEOUT_SEC);

        if (result == null || !result.isSuccess() || result.filledVolume <= 0) {
            String status = result != null ? result.status : "查询超时";
            log.warn("[实盘买入] {} orderId={} 未成交，状态={}", code, orderId, status);
            return null;
        }

        // 3. 用实盘实际成交价/数量构建 Order，写回 Portfolio
        double filledPrice  = result.filledPrice > 0 ? result.filledPrice : price;
        int    filledQty    = result.filledVolume;
        double amount       = filledPrice * filledQty;
        String exchange     = code.startsWith("6") ? "SH" : "SZ";
        FeeCalculator.FeeDetail fee = feeCalculator.calculateBuyFee(amount, exchange);

        Order order = Order.builder()
                .orderId(orderId)
                .stockCode(code)
                .stockName(stockName != null ? stockName : code)
                .orderType(Order.OrderType.BUY)
                .status(Order.OrderStatus.FILLED)
                .price(price)
                .quantity(quantity)
                .filledPrice(filledPrice)
                .filledQuantity(filledQty)
                .amount(amount)
                .commission(fee.commission)
                .stampTax(0)
                .transferFee(fee.transferFee)
                .totalFee(fee.total)
                .createTime(LocalDateTime.now())
                .filledTime(LocalDateTime.now())
                .strategyName(strategyName)
                .remark(reason)
                .build();

        boolean ok = portfolio.executeBuy(order);
        if (ok) {
            log.info("[实盘买入] {} 成交确认：{}股@{}（委托价={}）费用={}",
                    code, filledQty,
                    String.format("%.2f", filledPrice),
                    String.format("%.2f", price),
                    String.format("%.2f", fee.total));
        } else {
            log.error("[实盘买入] {} Portfolio.executeBuy 失败（资金不足？）", code);
            return null;
        }
        return order;
    }

    // =====================================================================
    //  卖出
    // =====================================================================

    /**
     * 提交实盘卖出委托，同步等待成交并将结果写回 Portfolio。
     *
     * @param portfolio    当前账户
     * @param code         股票代码
     * @param stockName    股票名称
     * @param price        委托价格
     * @param quantity     委托数量（股）
     * @param strategyName 策略名
     * @param reason       信号原因
     * @return 成交后的 Order 对象；失败返回 null
     */
    public Order submitSellLive(Portfolio portfolio,
                                 String code, String stockName,
                                 double price, int quantity,
                                 String strategyName, String reason) {
        Position pos = portfolio.getPosition(code);
        if (pos == null || pos.getAvailableQuantity() < quantity) {
            log.warn("[实盘卖出] {} 可用持仓不足 {}（可用={}），取消下单",
                    code, quantity, pos == null ? 0 : pos.getAvailableQuantity());
            return null;
        }

        log.info("[实盘卖出] {} {} {}股 @{}", code, stockName, quantity, String.format("%.2f", price));

        String remark  = (strategyName != null ? strategyName : "") +
                (reason != null ? " | " + reason : "");
        String orderId = brokerClient.placeSellOrder(code, price, quantity, remark);
        if (orderId == null) {
            log.error("[实盘卖出] {} 下单失败，QMT 返回 null orderId", code);
            return null;
        }

        QmtBrokerClient.OrderStatusResult result =
                orderTracker.waitForCompletion(orderId, code, "SELL", LIVE_ORDER_TIMEOUT_SEC);

        if (result == null || !result.isSuccess() || result.filledVolume <= 0) {
            String status = result != null ? result.status : "查询超时";
            log.warn("[实盘卖出] {} orderId={} 未成交，状态={}", code, orderId, status);
            return null;
        }

        double filledPrice  = result.filledPrice > 0 ? result.filledPrice : price;
        int    filledQty    = result.filledVolume;
        double amount       = filledPrice * filledQty;
        String exchange     = code.startsWith("6") ? "SH" : "SZ";
        FeeCalculator.FeeDetail fee = feeCalculator.calculateSellFee(amount, exchange);

        Order order = Order.builder()
                .orderId(orderId)
                .stockCode(code)
                .stockName(stockName != null ? stockName : code)
                .orderType(Order.OrderType.SELL)
                .status(Order.OrderStatus.FILLED)
                .price(price)
                .quantity(quantity)
                .filledPrice(filledPrice)
                .filledQuantity(filledQty)
                .amount(amount)
                .commission(fee.commission)
                .stampTax(fee.stampTax)
                .transferFee(fee.transferFee)
                .totalFee(fee.total)
                .createTime(LocalDateTime.now())
                .filledTime(LocalDateTime.now())
                .strategyName(strategyName)
                .remark(reason)
                .build();

        boolean ok = portfolio.executeSell(order);
        if (ok) {
            log.info("[实盘卖出] {} 成交确认：{}股@{}（委托价={}）费用={}",
                    code, filledQty,
                    String.format("%.2f", filledPrice),
                    String.format("%.2f", price),
                    String.format("%.2f", fee.total));
        } else {
            log.error("[实盘卖出] {} Portfolio.executeSell 失败", code);
            return null;
        }
        return order;
    }

    // =====================================================================
    //  持仓/资金同步（每分钟由 AutoTrader 定时调用）
    // =====================================================================

    /**
     * 从实盘同步持仓和资金到 Portfolio（带频率限制，60 秒最多同步一次）
     * <p>
     * 同步的内容：
     * - 实盘资产总额 → Portfolio.availableCash（以实盘为准）
     * - 实盘各持仓的可用数量（T+1 解锁状态）→ Position.availableQuantity
     * - 实盘各持仓的当前价格 → Position.currentPrice / marketValue
     * <p>
     * 注意：只同步「可用数量」和「当前价格」，不重建整个持仓列表，
     * 避免与 AutoTrader 内部逻辑冲突（如止损冷却、板块保护等状态）。
     *
     * @param portfolio 需要同步的账户
     */
    public void syncFromLiveIfDue(Portfolio portfolio) {
        long now = System.currentTimeMillis();
        if (now - lastSyncMs < SYNC_INTERVAL_MS) return;
        lastSyncMs = now;
        syncFromLive(portfolio);
    }

    /**
     * 立即从实盘同步（强制同步，不检查间隔）
     */
    public void syncFromLive(Portfolio portfolio) {
        if (!brokerClient.isAvailable()) {
            log.debug("[实盘同步] QMT 不可用，跳过同步");
            return;
        }

        // 1. 同步账户资金
        QmtBrokerClient.LiveAccountAsset asset = brokerClient.getLiveAccountAsset();
        if (asset != null && !asset.mock) {
            // 实盘可用资金以实盘为准（修正因下单延迟导致的模拟资金偏差）
            if (Math.abs(asset.availableCash - portfolio.getAvailableCash()) > 10) {
                log.info("[实盘同步] 可用资金校正: 模拟={} → 实盘={}",
                        String.format("%.2f", portfolio.getAvailableCash()),
                        String.format("%.2f", asset.availableCash));
                portfolio.setAvailableCash(asset.availableCash);
            }
        }

        // 2. 同步持仓可用数量（T+1 解锁状态以实盘为准）
        List<QmtBrokerClient.LivePosition> livePositions = brokerClient.getLivePositions();
        if (livePositions == null || livePositions.isEmpty()) {
            log.debug("[实盘同步] 实盘持仓为空");
            return;
        }

        for (QmtBrokerClient.LivePosition lp : livePositions) {
            Position pos = portfolio.getPosition(lp.stockCode);
            if (pos == null) {
                // 实盘有但模拟账户没有（手动买入/其他系统买入）→ 加入模拟持仓
                log.info("[实盘同步] 发现实盘持仓 {} {} 不在模拟账户中，同步加入",
                        lp.stockCode, lp.stockName);
                pos = Position.builder()
                        .stockCode(lp.stockCode)
                        .stockName(lp.stockName)
                        .quantity(lp.quantity)
                        .availableQuantity(lp.availableQuantity)
                        .avgCost(lp.avgCost)
                        .currentPrice(lp.currentPrice)
                        .marketValue(lp.marketValue)
                        .profit(lp.profit)
                        .profitRate(lp.profitRate)
                        .firstBuyTime(LocalDateTime.now())
                        .lastBuyDate(LocalDate.now())
                        .build();
                portfolio.getPositions().put(lp.stockCode, pos);
            } else {
                // 同步可用数量（T+1 实盘解锁时间比模拟判断更准确）
                if (pos.getAvailableQuantity() != lp.availableQuantity) {
                    log.debug("[实盘同步] {} 可用数量校正: 模拟={} → 实盘={}",
                            lp.stockCode, pos.getAvailableQuantity(), lp.availableQuantity);
                    pos.setAvailableQuantity(lp.availableQuantity);
                }
                // 同步当前价（实时价格更准）
                if (lp.currentPrice > 0) {
                    pos.updateCurrentPrice(lp.currentPrice);
                    pos.setMarketValue(pos.calculateMarketValue());
                    pos.setProfit(pos.calculateProfit());
                    pos.setProfitRate(pos.calculateProfitRate());
                }
            }
        }

        log.debug("[实盘同步] 完成：{}只持仓 资金={}", livePositions.size(), String.format("%.2f", portfolio.getAvailableCash()));
    }

    /**
     * 关闭适配器（释放追踪器线程）
     */
    public void shutdown() {
        orderTracker.shutdown();
    }
}


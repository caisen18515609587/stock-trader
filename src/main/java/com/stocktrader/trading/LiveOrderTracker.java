package com.stocktrader.trading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 实盘委托订单生命周期追踪器
 * <p>
 * 职责：
 * 1. 下单后异步轮询 QMT 委托状态，直到委托到达终态（FILLED / CANCELLED / REJECTED）
 * 2. 终态到达时回调 {@code onFilled} 或 {@code onFailed}，由调用方决定后续操作
 * 3. 超时保护：超过最大等待时间仍未终态，自动尝试撤单并触发失败回调
 * <p>
 * 设计原则：
 * - 轮询而非长连接，兼容 MiniQMT 异步回调已写入 qmt_bridge 内存缓存的场景
 * - 轮询间隔：3 秒（盘中委托通常 1~5 秒成交）
 * - 最大等待：180 秒（超出后撤单）
 */
public class LiveOrderTracker {

    private static final Logger log = LoggerFactory.getLogger(LiveOrderTracker.class);

    /** 轮询间隔（秒）*/
    private static final int POLL_INTERVAL_SEC = 3;

    /** 最大等待时间（秒），超时后自动撤单 */
    private static final int MAX_WAIT_SEC = 180;

    private final QmtBrokerClient client;
    private final ScheduledExecutorService scheduler;

    /**
     * @param client QMT 桥接客户端
     */
    public LiveOrderTracker(QmtBrokerClient client) {
        this.client    = client;
        this.scheduler = Executors.newScheduledThreadPool(2,
                r -> { Thread t = new Thread(r, "live-order-tracker"); t.setDaemon(true); return t; });
    }

    /**
     * 异步追踪委托：提交后立即返回，终态到达时回调通知。
     *
     * @param orderId    QMT 委托编号
     * @param stockCode  股票代码（仅用于日志）
     * @param orderType  "BUY" 或 "SELL"（仅用于日志）
     * @param onFilled   成交回调（参数为最新 {@link QmtBrokerClient.OrderStatusResult}）
     * @param onFailed   失败/撤单/超时回调（参数同上，可能为 null 表示查询失败）
     */
    public void trackAsync(
            String orderId,
            String stockCode,
            String orderType,
            Consumer<QmtBrokerClient.OrderStatusResult> onFilled,
            Consumer<QmtBrokerClient.OrderStatusResult> onFailed) {

        long deadline = System.currentTimeMillis() + MAX_WAIT_SEC * 1000L;
        log.info("[委托追踪] 开始追踪 {} {} orderId={} 超时={}s", orderType, stockCode, orderId, MAX_WAIT_SEC);

        // 使用 ScheduledFuture 包装持续轮询逻辑
        ScheduledFuture<?>[] futureHolder = new ScheduledFuture<?>[1];
        futureHolder[0] = scheduler.scheduleAtFixedRate(() -> {
            try {
                // 1. 超时检查
                if (System.currentTimeMillis() > deadline) {
                    log.warn("[委托追踪] {} {} orderId={} 超时未成交，尝试撤单",
                            orderType, stockCode, orderId);
                    client.cancelOrder(orderId);
                    // 撤单后再查一次最终状态
                    QmtBrokerClient.OrderStatusResult finalResult = client.getOrderStatus(orderId);
                    cancelTrack(futureHolder[0]);
                    if (onFailed != null) onFailed.accept(finalResult);
                    return;
                }

                // 2. 查询委托状态
                QmtBrokerClient.OrderStatusResult result = client.getOrderStatus(orderId);
                if (result == null) {
                    log.warn("[委托追踪] {} {} orderId={} 查询失败，继续轮询", orderType, stockCode, orderId);
                    return;
                }

                log.debug("[委托追踪] {} {} orderId={} 状态={} 成交{}/{}",
                        orderType, stockCode, orderId,
                        result.status, result.filledVolume, result.volume);

                // 3. 判断终态
                if (!result.isTerminal()) {
                    return;  // 非终态，继续轮询
                }

                cancelTrack(futureHolder[0]);

                if (result.isSuccess()) {
                    log.info("[委托追踪] {} {} orderId={} 成交完成 {}股@{}",
                            orderType, stockCode, orderId, result.filledVolume,
                            String.format("%.2f", result.filledPrice));
                    if (onFilled != null) onFilled.accept(result);
                } else {
                    log.warn("[委托追踪] {} {} orderId={} 终态={} 未成交",
                            orderType, stockCode, orderId, result.status);
                    if (onFailed != null) onFailed.accept(result);
                }

            } catch (Exception e) {
                log.error("[委托追踪] {} {} orderId={} 轮询异常: {}",
                        orderType, stockCode, orderId, e.getMessage(), e);
            }
        }, POLL_INTERVAL_SEC, POLL_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    /**
     * 同步等待委托完成（阻塞当前线程直到终态或超时）
     * <p>
     * 适用于需要在信号触发时同步拿到成交价/成交量的场景。
     *
     * @param orderId   QMT 委托编号
     * @param stockCode 股票代码（日志）
     * @param orderType "BUY" / "SELL"（日志）
     * @param timeoutSec 超时秒数（建议与 MAX_WAIT_SEC 一致）
     * @return 最终委托状态；查询失败或超时则返回 null
     */
    public QmtBrokerClient.OrderStatusResult waitForCompletion(
            String orderId, String stockCode, String orderType, int timeoutSec) {

        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        log.info("[委托同步等待] {} {} orderId={} 最长等待{}s", orderType, stockCode, orderId, timeoutSec);

        while (System.currentTimeMillis() < deadline) {
            QmtBrokerClient.OrderStatusResult result = client.getOrderStatus(orderId);
            if (result != null && result.isTerminal()) {
                log.info("[委托同步等待] {} {} orderId={} 终态={} 成交{}/{}@{}",
                        orderType, stockCode, orderId,
                        result.status, result.filledVolume, result.volume,
                        String.format("%.2f", result.filledPrice));
                return result;
            }
            try {
                Thread.sleep(POLL_INTERVAL_SEC * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[委托同步等待] 中断: {} {}", orderType, stockCode);
                return null;
            }
        }

        log.warn("[委托同步等待] {} {} orderId={} 超时 {}s，尝试撤单", orderType, stockCode, orderId, timeoutSec);
        client.cancelOrder(orderId);
        return client.getOrderStatus(orderId);
    }

    /**
     * 关闭追踪器（释放线程池）
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void cancelTrack(ScheduledFuture<?> future) {
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
        }
    }
}


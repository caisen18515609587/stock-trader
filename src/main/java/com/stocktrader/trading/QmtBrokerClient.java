package com.stocktrader.trading;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * QMT 实盘桥接客户端
 * <p>
 * 通过 HTTP 调用本地 qmt_bridge.py 服务，实现：
 * - 买入/卖出下单
 * - 撤单
 * - 查询委托状态
 * - 查询实盘持仓
 * - 查询账户资金
 * <p>
 * qmt_bridge.py 默认运行在 localhost:8098，可通过构造函数自定义。
 * 当 qmt_bridge.py 不可用时，{@link #isAvailable()} 返回 false，
 * 调用方（{@link LiveBrokerAdapter}）会自动降级为模拟交易模式。
 */
public class QmtBrokerClient {

    private static final Logger log = LoggerFactory.getLogger(QmtBrokerClient.class);

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    /** qmt_bridge.py 服务地址 */
    private final String baseUrl;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** 是否可用（连通性缓存，避免每次下单都 ping 一次）*/
    private volatile boolean available = false;
    private volatile long lastHealthCheckMs = 0L;
    private static final long HEALTH_CACHE_MS = 30_000L;  // 30 秒刷新一次可用性

    public QmtBrokerClient(String bridgeUrl) {
        this.baseUrl      = bridgeUrl.endsWith("/") ? bridgeUrl.substring(0, bridgeUrl.length() - 1) : bridgeUrl;
        this.objectMapper = new ObjectMapper();
        this.httpClient   = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /** 默认连接本地 qmt_bridge.py */
    public QmtBrokerClient() {
        this("http://localhost:8098");
    }

    // =====================================================================
    //  连通性
    // =====================================================================

    /**
     * 检查 qmt_bridge 是否可用（带缓存，30 秒内不重复 ping）
     */
    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (now - lastHealthCheckMs < HEALTH_CACHE_MS) {
            return available;
        }
        lastHealthCheckMs = now;
        try {
            String resp = httpGet("/health");
            JsonNode node = objectMapper.readTree(resp);
            available = "ok".equals(node.path("status").asText());
            if (available) {
                boolean mock = node.path("mock_mode").asBoolean(true);
                log.debug("[QMT桥接] 连接正常，mock_mode={}, account={}",
                        mock, node.path("account_id").asText());
            }
        } catch (Exception e) {
            available = false;
            log.debug("[QMT桥接] 不可用: {}", e.getMessage());
        }
        return available;
    }

    /**
     * 强制刷新连通性检查
     */
    public boolean checkAvailableForce() {
        lastHealthCheckMs = 0L;
        return isAvailable();
    }

    // =====================================================================
    //  下单接口
    // =====================================================================

    /**
     * 买入下单
     *
     * @param stockCode 6 位股票代码
     * @param price     委托价格（0 = 市价）
     * @param volume    委托数量（必须为 100 的整数倍）
     * @param remark    备注（策略名等，传给 QMT 作为订单备注）
     * @return orderId（QMT 返回的委托编号）；失败时返回 null
     */
    public String placeBuyOrder(String stockCode, double price, int volume, String remark) {
        return placeOrder("buy", stockCode, price, volume, remark);
    }

    /**
     * 卖出下单
     */
    public String placeSellOrder(String stockCode, double price, int volume, String remark) {
        return placeOrder("sell", stockCode, price, volume, remark);
    }

    private String placeOrder(String side, String stockCode, double price, int volume, String remark) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("stock_code", stockCode);
            body.put("price",      price);
            body.put("volume",     volume);
            body.put("remark",     remark != null ? remark : "");

            String resp = httpPost("/order/" + side, body.toString());
            JsonNode node = objectMapper.readTree(resp);

            if (node.path("success").asBoolean(false)) {
                String orderId = node.path("order_id").asText();
                boolean mock   = node.path("mock").asBoolean(true);
                log.info("[QMT桥接] {} {} {}股@{} → orderId={} mock={}",
                        side.toUpperCase(), stockCode, volume, String.format("%.2f", price), orderId, mock);
                return orderId;
            } else {
                String err = node.path("error").asText("未知错误");
                log.error("[QMT桥接] {} {} 失败: {}", side.toUpperCase(), stockCode, err);
                return null;
            }
        } catch (Exception e) {
            log.error("[QMT桥接] {} {} 异常: {}", side.toUpperCase(), stockCode, e.getMessage());
            available = false;  // 标记不可用，触发重连
            return null;
        }
    }

    // =====================================================================
    //  撤单
    // =====================================================================

    /**
     * 撤单
     *
     * @param orderId QMT 委托编号
     * @return true = 撤单指令已发出（不代表一定成功撤回）
     */
    public boolean cancelOrder(String orderId) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("order_id", orderId);
            String resp = httpPost("/order/cancel", body.toString());
            JsonNode node = objectMapper.readTree(resp);
            boolean ok = node.path("success").asBoolean(false);
            log.info("[QMT桥接] 撤单 orderId={} 结果={}", orderId, ok ? "成功" : "失败");
            return ok;
        } catch (Exception e) {
            log.error("[QMT桥接] 撤单 orderId={} 异常: {}", orderId, e.getMessage());
            return false;
        }
    }

    // =====================================================================
    //  查询委托状态
    // =====================================================================

    /**
     * 查询单笔委托的最新状态
     *
     * @param orderId QMT 委托编号
     * @return OrderStatusResult；如果查询失败返回 null
     */
    public OrderStatusResult getOrderStatus(String orderId) {
        try {
            String resp = httpGet("/order/status/" + orderId);
            JsonNode node = objectMapper.readTree(resp);
            if (node.has("error")) {
                log.warn("[QMT桥接] 查委托 {} 失败: {}", orderId, node.path("error").asText());
                return null;
            }
            return parseOrderStatus(node);
        } catch (Exception e) {
            log.error("[QMT桥接] 查委托 {} 异常: {}", orderId, e.getMessage());
            return null;
        }
    }

    /**
     * 查询今日所有委托
     */
    public List<OrderStatusResult> getTodayOrders() {
        List<OrderStatusResult> list = new ArrayList<>();
        try {
            String resp = httpGet("/orders/today");
            JsonNode arr = objectMapper.readTree(resp);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    list.add(parseOrderStatus(node));
                }
            }
        } catch (Exception e) {
            log.error("[QMT桥接] 查今日委托异常: {}", e.getMessage());
        }
        return list;
    }

    private OrderStatusResult parseOrderStatus(JsonNode node) {
        OrderStatusResult r = new OrderStatusResult();
        r.orderId       = node.path("order_id").asText();
        r.stockCode     = node.path("stock_code").asText();
        r.orderType     = node.path("order_type").asText();   // BUY / SELL
        r.price         = node.path("price").asDouble();
        r.volume        = node.path("volume").asInt();
        r.filledVolume  = node.path("filled_volume").asInt();
        r.filledPrice   = node.path("filled_price").asDouble();
        r.status        = node.path("status").asText();       // PENDING/SUBMITTED/PARTIAL_FILLED/FILLED/CANCELLED/REJECTED
        r.orderTime     = node.path("order_time").asText();
        r.remark        = node.path("remark").asText();
        r.mock          = node.path("mock").asBoolean(false);
        return r;
    }

    // =====================================================================
    //  查询持仓
    // =====================================================================

    /**
     * 查询实盘持仓列表
     */
    public List<LivePosition> getLivePositions() {
        List<LivePosition> list = new ArrayList<>();
        try {
            String resp = httpGet("/positions");
            JsonNode arr = objectMapper.readTree(resp);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    LivePosition p = new LivePosition();
                    p.stockCode         = node.path("stock_code").asText();
                    p.stockName         = node.path("stock_name").asText();
                    p.quantity          = node.path("quantity").asInt();
                    p.availableQuantity = node.path("available_quantity").asInt();
                    p.avgCost           = node.path("avg_cost").asDouble();
                    p.currentPrice      = node.path("current_price").asDouble();
                    p.marketValue       = node.path("market_value").asDouble();
                    p.profit            = node.path("profit").asDouble();
                    p.profitRate        = node.path("profit_rate").asDouble();
                    list.add(p);
                }
            }
        } catch (Exception e) {
            log.error("[QMT桥接] 查持仓异常: {}", e.getMessage());
        }
        return list;
    }

    // =====================================================================
    //  查询账户资金
    // =====================================================================

    /**
     * 查询实盘账户资金
     */
    public LiveAccountAsset getLiveAccountAsset() {
        try {
            String resp = httpGet("/account");
            JsonNode node = objectMapper.readTree(resp);
            if (node.has("error")) {
                log.warn("[QMT桥接] 查资金失败: {}", node.path("error").asText());
                return null;
            }
            LiveAccountAsset a = new LiveAccountAsset();
            a.totalAssets   = node.path("total_assets").asDouble();
            a.availableCash = node.path("available_cash").asDouble();
            a.frozenCash    = node.path("frozen_cash").asDouble();
            a.marketValue   = node.path("market_value").asDouble();
            a.profit        = node.path("profit").asDouble();
            a.profitRate    = node.path("profit_rate").asDouble();
            a.mock          = node.path("mock").asBoolean(false);
            return a;
        } catch (Exception e) {
            log.error("[QMT桥接] 查资金异常: {}", e.getMessage());
            return null;
        }
    }

    // =====================================================================
    //  HTTP 工具方法
    // =====================================================================

    private String httpGet(String path) throws IOException {
        Request req = new Request.Builder()
                .url(baseUrl + path)
                .get()
                .build();
        try (Response resp = httpClient.newCall(req).execute()) {
            if (resp.body() == null) return "{}";
            return resp.body().string();
        }
    }

    private String httpPost(String path, String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON_TYPE);
        Request req = new Request.Builder()
                .url(baseUrl + path)
                .post(body)
                .build();
        try (Response resp = httpClient.newCall(req).execute()) {
            if (resp.body() == null) return "{}";
            return resp.body().string();
        }
    }

    // =====================================================================
    //  内部数据结构
    // =====================================================================

    /** 委托状态结果 */
    public static class OrderStatusResult {
        public String  orderId;
        public String  stockCode;
        public String  orderType;      // BUY / SELL
        public double  price;
        public int     volume;
        public int     filledVolume;
        public double  filledPrice;
        /** PENDING / SUBMITTED / PARTIAL_FILLED / FILLED / CANCELLED / REJECTED */
        public String  status;
        public String  orderTime;
        public String  remark;
        public boolean mock;

        public boolean isTerminal() {
            return "FILLED".equals(status) || "CANCELLED".equals(status) || "REJECTED".equals(status);
        }

        public boolean isSuccess() {
            return "FILLED".equals(status) || "PARTIAL_FILLED".equals(status);
        }

        @Override
        public String toString() {
            return String.format("Order[%s %s %s %d/%d@%.2f %s]",
                    orderId, orderType, stockCode, filledVolume, volume, filledPrice, status);
        }
    }

    /** 实盘持仓 */
    public static class LivePosition {
        public String stockCode;
        public String stockName;
        public int    quantity;
        public int    availableQuantity;
        public double avgCost;
        public double currentPrice;
        public double marketValue;
        public double profit;
        public double profitRate;
    }

    /** 实盘账户资金 */
    public static class LiveAccountAsset {
        public double  totalAssets;
        public double  availableCash;
        public double  frozenCash;
        public double  marketValue;
        public double  profit;
        public double  profitRate;
        public boolean mock;
    }
}


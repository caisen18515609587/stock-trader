package com.stocktrader.util;

import com.stocktrader.config.DatabaseManager;
import com.stocktrader.model.Portfolio;
import com.stocktrader.model.Position;
import com.stocktrader.trading.AccountPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 交易反馈回调服务器（轻量级 HTTP 服务器）
 * <p>
 * 功能说明：
 * 系统在产生交易信号并完成模拟交易后，会通过微信推送告知用户。
 * 用户在实盘操作后，可通过以下两种方式反馈结果给系统：
 * <p>
 * 方式一：浏览器访问（推荐，手机微信内置浏览器即可）
 *   买入成功：http://你的IP:8888/callback?action=buy_ok&code=000547&price=10.50&qty=1000
 *   买入失败：http://你的IP:8888/callback?action=buy_fail&code=000547
 *   卖出成功：http://你的IP:8888/callback?action=sell_ok&code=000547&price=11.20&qty=500
 *   卖出失败：http://你的IP:8888/callback?action=sell_fail&code=000547
 *   查看账户：http://你的IP:8888/status
 * <p>
 * 方式二：在微信推送消息中，Server酱 Webhook 转发（需要额外配置）
 * <p>
 * 系统收到反馈后会自动更新模拟账户，确保模拟账户与实盘保持一致。
 */
public class TradeCallbackServer {

    private static final Logger log = LoggerFactory.getLogger(TradeCallbackServer.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final int port;
    private final Portfolio portfolio;
    private final AccountPersistence persistence;
    private final WechatPushService pushService;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TradeCallbackServer(int port, Portfolio portfolio, AccountPersistence persistence,
                                WechatPushService pushService) {
        this.port = port;
        this.portfolio = portfolio;
        this.persistence = persistence;
        this.pushService = pushService;
        this.executor = Executors.newCachedThreadPool();
    }

    /**
     * 启动HTTP回调服务器（异步，不阻塞主线程）
     */
    public void start() {
        if (running.get()) return;
        running.set(true);

        executor.submit(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                log.info("交易反馈服务器已启动，监听端口: {}", port);
                log.info("手机浏览器访问: http://电脑IP:{}/status 查看账户状态", port);
                log.info("反馈买入成功: http://电脑IP:{}/callback?action=buy_ok&code=股票代码&price=价格&qty=数量", port);
                log.info("反馈卖出成功: http://电脑IP:{}/callback?action=sell_ok&code=股票代码&price=价格&qty=数量", port);

                while (running.get() && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        executor.submit(() -> handleRequest(client));
                    } catch (IOException e) {
                        if (running.get()) {
                            log.error("接受连接失败: {}", e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                log.error("回调服务器启动失败（端口{}可能被占用）: {}", port, e.getMessage());
            }
        });
    }

    /**
     * 停止服务器
     */
    public void stop() {
        running.set(false);
        executor.shutdownNow();
        log.info("交易反馈服务器已停止");
    }

    /**
     * 处理一次 HTTP 请求
     */
    private void handleRequest(Socket client) {
        try (
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream()
        ) {
            // 读取请求行
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            // 解析路径和参数（GET /path?key=val HTTP/1.1）
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String fullPath = parts[1];

            String path;
            String queryString = "";
            int qIdx = fullPath.indexOf('?');
            if (qIdx >= 0) {
                path = fullPath.substring(0, qIdx);
                queryString = fullPath.substring(qIdx + 1);
            } else {
                path = fullPath;
            }

            String responseBody;
            if ("/callback".equals(path)) {
                responseBody = handleCallback(queryString);
            } else if ("/status".equals(path)) {
                responseBody = handleStatus();
            } else {
                responseBody = buildHelpPage();
            }

            // 返回 HTTP 响应（Content-Type: text/html; charset=UTF-8）
            byte[] bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Content-Length: " + bodyBytes.length + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(bodyBytes);
            out.flush();

        } catch (Exception e) {
            log.debug("处理请求异常: {}", e.getMessage());
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 处理 /callback 请求
     * action=buy_ok   买入成功（实盘已成交）
     * action=buy_fail 买入失败（实盘未成交，撤回模拟买入）
     * action=sell_ok  卖出成功（实盘已成交）
     * action=sell_fail 卖出失败（实盘未成交，撤回模拟卖出）
     */
    private synchronized String handleCallback(String queryString) {
        java.util.Map<String, String> params = parseQuery(queryString);
        String action = params.getOrDefault("action", "");
        String code = params.getOrDefault("code", "");
        String priceStr = params.getOrDefault("price", "0");
        String qtyStr = params.getOrDefault("qty", "0");

        double price = 0;
        int qty = 0;
        try { price = Double.parseDouble(priceStr); } catch (Exception ignored) {}
        try { qty = Integer.parseInt(qtyStr); } catch (Exception ignored) {}

        String msg;
        String now = LocalDateTime.now().format(DT_FMT);

        switch (action.toLowerCase()) {
            case "buy_ok":
                // 用户实盘买入成功，系统记录确认
                msg = handleBuyOk(code, price, qty, now);
                break;
            case "buy_fail":
                // 用户实盘买入失败，撤回模拟买入
                msg = handleBuyFail(code, now);
                break;
            case "sell_ok":
                // 用户实盘卖出成功，系统记录确认
                msg = handleSellOk(code, price, qty, now);
                break;
            case "sell_fail":
                // 用户实盘卖出失败，撤回模拟卖出（暂时只记录，不自动回滚）
                msg = handleSellFail(code, now);
                break;
            default:
                msg = "❌ 未知操作: " + action + "，请查看帮助页面";
        }

        log.info("[用户反馈] {} action={} code={} price={} qty={}", now, action, code, price, qty);

        return buildResponsePage("交易反馈结果", msg, now);
    }

    private String handleBuyOk(String code, double price, int qty, String now) {
        Position pos = portfolio.getPosition(code);
        if (pos == null) {
            return String.format("⚠️ 未找到 %s 的持仓记录（模拟账户中无此股票），如需手动记录请联系管理员", code);
        }
        // 买入已在模拟账户中记录，此处仅做确认日志
        persistence.save(portfolio);
        String detail = String.format(
                "✅ 买入确认成功！\n股票：%s\n实际成交价：%.2f 元\n实际数量：%d 股\n模拟账户已同步",
                code, price > 0 ? price : pos.getAvgCost(), qty > 0 ? qty : pos.getQuantity()
        );
        log.info("[确认买入] {} 价格:{} 数量:{}", code, price, qty);

        if (pushService.isEnabled()) {
            pushService.sendMessage("✅ 买入已确认 - " + code,
                    "## ✅ 买入操作已确认\n\n" +
                    "| 项目 | 内容 |\n|------|------|\n" +
                    String.format("| 股票 | %s |\n| 实际价格 | %.2f 元 |\n| 实际数量 | %d 股 |\n| 确认时间 | %s |",
                            code, price, qty, now));
        }
        return detail;
    }

    private String handleBuyFail(String code, String now) {
        Position pos = portfolio.getPosition(code);
        if (pos == null) {
            return String.format("⚠️ 未找到 %s 的持仓记录，可能已被处理", code);
        }
        String stockName = pos.getStockName() != null ? pos.getStockName() : code;
        int qty = pos.getQuantity();
        double avgCost = pos.getAvgCost();
        // 撤回买入：将该持仓从账户中移除，资金返还（含手续费退回）
        double simAmount = qty * avgCost;
        double refund = simAmount + simAmount * 0.0003; // 近似退回含印花税
        portfolio.getPositions().remove(code);
        portfolio.setAvailableCash(portfolio.getAvailableCash() + refund);
        persistence.save(portfolio);

        // 写入撤销记录
        String reason = String.format("实盘买入失败，撤回模拟买入持仓 %d 股 @%.2f", qty, avgCost);
        saveRollbackRecord("BUY_FAIL", code, stockName, avgCost, qty, simAmount, refund, reason, now);

        String detail = String.format(
                "🔄 买入已撤回！\n股票：%s %s\n撤回数量：%d 股\n模拟成交价：%.2f 元\n返还资金（约）：%,.2f 元\n模拟账户已恢复",
                code, stockName, qty, avgCost, refund
        );
        log.warn("[撤回买入] {} {} 数量:{} 退回资金:{}", code, stockName, qty, refund);

        if (pushService.isEnabled()) {
            pushService.sendMessage("🔄 买入已撤回 - " + code + " " + stockName,
                    "## 🔄 买入操作已撤回\n\n" +
                    String.format("股票 **%s %s** 实盘买入失败，模拟账户已撤回该持仓。\n\n" +
                            "| 项目 | 内容 |\n|------|------|\n" +
                            "| 撤回数量 | %d 股 |\n" +
                            "| 模拟成交价 | %.2f 元 |\n" +
                            "| 返还资金 | %,.2f 元 |\n" +
                            "| 撤销时间 | %s |",
                            code, stockName, qty, avgCost, refund, now));
        }
        return detail;
    }

    private String handleSellOk(String code, double price, int qty, String now) {
        // 卖出已在模拟账户中处理，此处确认
        persistence.save(portfolio);
        String detail = String.format(
                "✅ 卖出确认成功！\n股票：%s\n实际成交价：%.2f 元\n实际数量：%d 股\n模拟账户已同步",
                code, price, qty
        );
        log.info("[确认卖出] {} 价格:{} 数量:{}", code, price, qty);

        if (pushService.isEnabled()) {
            pushService.sendMessage("✅ 卖出已确认 - " + code,
                    "## ✅ 卖出操作已确认\n\n" +
                    "| 项目 | 内容 |\n|------|------|\n" +
                    String.format("| 股票 | %s |\n| 实际价格 | %.2f 元 |\n| 实际数量 | %d 股 |\n| 确认时间 | %s |",
                            code, price, qty, now));
        }
        return detail;
    }

    private String handleSellFail(String code, String now) {
        // 卖出失败回滚：从已平仓记录中找到最近一笔该股票的卖出，将其撤回
        // 恢复持仓：将股票重新加入账户，并扣回已到账的资金
        // 查找最近一笔该股票的卖出历史，找到成交价和数量
        double simSellPrice = 0;
        int simQty = 0;
        String stockName = code;
        double simAmount = 0;
        double deductCash = 0;

        // 从 t_order_history 里找最近一笔该账户该股票的 SELL 成交记录
        // [P0-1 优化] 使用独立短连接
        try {
            Connection conn = DatabaseManager.getInstance().newConnection();
            String sql = "SELECT stock_name, filled_price, filled_quantity, amount " +
                         "FROM t_order_history WHERE account_id=? AND stock_code=? AND order_type='SELL' " +
                         "ORDER BY filled_time DESC LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, portfolio.getAccountId());
                ps.setString(2, code);
                java.sql.ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    stockName = rs.getString("stock_name") != null ? rs.getString("stock_name") : code;
                    simSellPrice = rs.getDouble("filled_price");
                    simQty = rs.getInt("filled_quantity");
                    simAmount = rs.getDouble("amount");
                }
            } finally {
                try { conn.close(); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            log.warn("[卖出失败回滚] 查询历史订单失败: {}", e.getMessage());
        }

        // 若查到了卖出记录，将持仓恢复，并从可用资金中扣回已到账的卖出款
        if (simQty > 0 && simSellPrice > 0) {
            // 扣回卖出到账资金（约等于 amount - 手续费，这里直接扣 simAmount 保守估算）
            deductCash = simAmount;
            double cashAfter = portfolio.getAvailableCash() - deductCash;
            if (cashAfter < 0) cashAfter = 0; // 防止资金为负

            // 恢复持仓（若该股票持仓已存在则累加，否则新建）
            Position existing = portfolio.getPosition(code);
            if (existing != null) {
                existing.setQuantity(existing.getQuantity() + simQty);
                existing.setAvailableQuantity(existing.getAvailableQuantity() + simQty);
            } else {
                Position restored = Position.builder()
                        .stockCode(code)
                        .stockName(stockName)
                        .quantity(simQty)
                        .availableQuantity(simQty)
                        .avgCost(simSellPrice)
                        .currentPrice(simSellPrice)
                        .marketValue(simSellPrice * simQty)
                        .build();
                portfolio.getPositions().put(code, restored);
            }
            portfolio.setAvailableCash(cashAfter);
            persistence.save(portfolio);

            String reason = String.format("实盘卖出失败，撤回模拟卖出 %d 股 @%.2f，恢复持仓", simQty, simSellPrice);
            saveRollbackRecord("SELL_FAIL", code, stockName, simSellPrice, simQty, simAmount, deductCash, reason, now);

            log.warn("[撤回卖出] {} {} 数量:{} @{} 扣回资金:{}", code, stockName, simQty, simSellPrice, deductCash);

            String detail = String.format(
                    "🔄 卖出已撤回！\n股票：%s %s\n撤回数量：%d 股\n模拟卖出价：%.2f 元\n" +
                    "已扣回到账资金（约）：%,.2f 元\n持仓已恢复，模拟账户已同步",
                    code, stockName, simQty, simSellPrice, deductCash
            );

            if (pushService.isEnabled()) {
                pushService.sendMessage("🔄 卖出已撤回 - " + code + " " + stockName,
                        "## 🔄 卖出操作已撤回\n\n" +
                        String.format("股票 **%s %s** 实盘卖出未成交，模拟账户已撤回该卖出操作，持仓已恢复。\n\n" +
                                "| 项目 | 内容 |\n|------|------|\n" +
                                "| 撤回数量 | %d 股 |\n" +
                                "| 模拟卖出价 | %.2f 元 |\n" +
                                "| 扣回资金 | %,.2f 元 |\n" +
                                "| 撤销时间 | %s |",
                                code, stockName, simQty, simSellPrice, deductCash, now));
            }
            return detail;
        } else {
            // 无法找到卖出记录，仅记录警告
            String reason = "实盘卖出失败，未找到对应模拟卖出记录，仅记录告警";
            saveRollbackRecord("SELL_FAIL", code, stockName, 0, 0, 0, 0, reason, now);
            log.warn("[卖出失败] {} 未找到对应卖出记录，无法自动回滚", code);
            String detail = String.format(
                    "⚠️ 卖出失败已记录！\n股票：%s\n未找到对应的模拟卖出记录，无法自动撤回。\n请手动核查持仓并注意实盘风险。", code);
            if (pushService.isEnabled()) {
                pushService.sendMessage("⚠️ 卖出失败提醒 - " + code,
                        "## ⚠️ 卖出操作失败提醒\n\n" +
                        String.format("股票 **%s** 实盘卖出未成交，未找到对应的模拟卖出记录，无法自动撤回。\n\n" +
                                "**请手动核查持仓并注意实盘风险！**\n\n记录时间：%s", code, now));
            }
            return detail;
        }
    }

    /**
     * 写入撤销记录到 t_rollback_record 表
     */
    private void saveRollbackRecord(String rollbackType, String stockCode, String stockName,
                                     double simPrice, int simQty, double simAmount,
                                     double refundCash, String reason, String rollbackTime) {
        // [P0-1 优化] 使用独立短连接
        Connection conn = null;
        try {
            conn = DatabaseManager.getInstance().newConnection();
            String sql = "INSERT INTO t_rollback_record " +
                    "(account_id, rollback_type, stock_code, stock_name, sim_price, sim_quantity, " +
                    " sim_amount, refund_cash, rollback_reason, rollback_time) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, portfolio.getAccountId());
                ps.setString(2, rollbackType);
                ps.setString(3, stockCode);
                ps.setString(4, stockName);
                ps.setDouble(5, simPrice);
                ps.setInt(6, simQty);
                ps.setDouble(7, simAmount);
                ps.setDouble(8, refundCash);
                ps.setString(9, reason);
                ps.setString(10, rollbackTime);
                ps.executeUpdate();
            }
            log.info("[撤销记录] 已保存: {} {} {} {}股", rollbackType, stockCode, stockName, simQty);
        } catch (Exception e) {
            log.error("[撤销记录] 写入失败: {}", e.getMessage());
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception ignore) {}
            }
        }
    }

    /**
     * 处理 /status 请求，显示当前账户状态
     */
    private String handleStatus() {
        StringBuilder sb = new StringBuilder();
        double totalAssets = portfolio.getTotalAssets();
        double totalProfit = totalAssets - portfolio.getInitialCapital();
        double totalReturn = portfolio.getInitialCapital() > 0
                ? totalProfit / portfolio.getInitialCapital() * 100 : 0;

        sb.append("<h2>📊 账户实时状态</h2>");
        sb.append("<table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse'>");
        sb.append(tr("初始资金", String.format("%,.2f 元", portfolio.getInitialCapital())));
        sb.append(tr("当前总资产", String.format("<b>%,.2f 元</b>", totalAssets)));
        sb.append(tr("可用资金", String.format("%,.2f 元", portfolio.getAvailableCash())));
        sb.append(tr("持仓市值", String.format("%,.2f 元", portfolio.getTotalPositionValue())));
        sb.append(tr("总盈亏", String.format("<b style='color:%s'>%+,.2f 元（%+.2f%%）</b>",
                totalProfit >= 0 ? "green" : "red", totalProfit, totalReturn)));
        sb.append("</table>");

        if (!portfolio.getPositions().isEmpty()) {
            sb.append("<h3>持仓明细</h3>");
            sb.append("<table border='1' cellpadding='6' cellspacing='0' style='border-collapse:collapse'>");
            sb.append("<tr><th>代码</th><th>名称</th><th>持仓(股)</th><th>可用(股)</th><th>成本</th><th>现价</th><th>盈亏</th></tr>");
            portfolio.getPositions().forEach((code, pos) -> {
                double posReturn = pos.getAvgCost() > 0
                        ? (pos.getCurrentPrice() - pos.getAvgCost()) / pos.getAvgCost() * 100 : 0;
                sb.append(String.format(
                        "<tr><td>%s</td><td>%s</td><td>%d</td><td>%d</td><td>%.2f</td><td>%.2f</td>" +
                        "<td style='color:%s'><b>%+.2f%%</b></td></tr>",
                        code, pos.getStockName() != null ? pos.getStockName() : code,
                        pos.getQuantity(), pos.getAvailableQuantity(),
                        pos.getAvgCost(), pos.getCurrentPrice(),
                        posReturn >= 0 ? "green" : "red", posReturn
                ));
            });
            sb.append("</table>");
        } else {
            sb.append("<p>当前空仓</p>");
        }

        sb.append("<p><small>更新时间：").append(LocalDateTime.now().format(DT_FMT)).append("</small></p>");
        sb.append("<p><a href='/status'>🔄 刷新</a> &nbsp; <a href='/'>返回帮助</a></p>");

        return buildPage("账户状态 - 股票交易系统", sb.toString());
    }

    /**
     * 帮助页面
     */
    private String buildHelpPage() {
        StringBuilder sb = new StringBuilder();
        sb.append("<h2>📱 交易反馈操作指南</h2>");
        sb.append("<p>当系统推送交易信号后，请在实盘操作完成后，点击对应链接反馈结果：</p>");
        sb.append("<h3>🔗 快捷操作链接</h3>");
        sb.append("<table border='1' cellpadding='8' cellspacing='0' style='border-collapse:collapse'>");
        sb.append("<tr><th>操作</th><th>URL示例</th><th>说明</th></tr>");
        sb.append("<tr><td>买入成功</td><td><a href='/callback?action=buy_ok&code=000001&price=10.50&qty=1000'>/callback?action=buy_ok&code=代码&price=价格&qty=数量</a></td><td>实盘买入已成交</td></tr>");
        sb.append("<tr><td>买入失败</td><td><a href='/callback?action=buy_fail&code=000001'>/callback?action=buy_fail&code=代码</a></td><td>实盘买入未成交，撤回模拟</td></tr>");
        sb.append("<tr><td>卖出成功</td><td><a href='/callback?action=sell_ok&code=000001&price=11.20&qty=500'>/callback?action=sell_ok&code=代码&price=价格&qty=数量</a></td><td>实盘卖出已成交</td></tr>");
        sb.append("<tr><td>卖出失败</td><td><a href='/callback?action=sell_fail&code=000001'>/callback?action=sell_fail&code=代码</a></td><td>实盘卖出未成交</td></tr>");
        sb.append("<tr><td>查看账户</td><td><a href='/status'>/status</a></td><td>查看当前账户持仓状态</td></tr>");
        sb.append("</table>");
        sb.append("<p style='color:gray'>提示：将以上链接收藏到手机浏览器，收到微信推送后点击对应链接即可快速反馈。</p>");
        return buildPage("交易反馈 - 股票交易系统", sb.toString());
    }

    private String buildResponsePage(String title, String msg, String time) {
        String content = "<h2>" + title + "</h2>" +
                "<div style='background:#f0f8ff;padding:16px;border-radius:8px;white-space:pre-wrap'>" +
                msg.replace("\n", "<br/>") + "</div>" +
                "<p><small>处理时间：" + time + "</small></p>" +
                "<p><a href='/status'>📊 查看账户状态</a> &nbsp; <a href='/'>返回帮助</a></p>";
        return buildPage(title + " - 股票交易系统", content);
    }

    private String buildPage(String title, String body) {
        return "<!DOCTYPE html><html><head>" +
                "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>" + title + "</title>" +
                "<style>body{font-family:Arial,sans-serif;margin:20px;max-width:800px}table{width:100%}th,td{text-align:left}</style>" +
                "</head><body>" +
                "<h1 style='color:#1a73e8'>📈 股票交易系统</h1>" +
                body +
                "</body></html>";
    }

    private String tr(String key, String value) {
        return "<tr><td><b>" + key + "</b></td><td>" + value + "</td></tr>";
    }

    private java.util.Map<String, String> parseQuery(String query) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = pair.substring(0, eq);
                String v = pair.substring(eq + 1);
                try {
                    v = java.net.URLDecoder.decode(v, "UTF-8");
                } catch (Exception ignored) {}
                map.put(k, v);
            }
        }
        return map;
    }
}


package com.stocktrader.model;

import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 投资组合（账户）模型
 * 管理资金、持仓、交易历史
 */
@Data
public class Portfolio {

    private static final Logger log = LoggerFactory.getLogger(Portfolio.class);

    /** 账户ID */
    private String accountId;

    /** 账户名称 */
    private String accountName;

    /** 初始资金（元） */
    private double initialCapital;

    /** 可用资金（元） */
    private double availableCash;

    /** 冻结资金（委托中的资金） */
    private double frozenCash;

    /** 当前持仓 stockCode -> Position */
    private Map<String, Position> positions;

    /** 交易历史 */
    private List<Order> orderHistory;

    /** 已平仓记录（含已实现盈亏明细） */
    private List<ClosedPosition> closedPositions;

    /** 累计已实现盈亏（元） */
    private double realizedPnl;

    /** 账户创建时间 */
    private LocalDateTime createTime;

    /** 最后更新时间 */
    private LocalDateTime lastUpdateTime;

    /** 账户模式 */
    private AccountMode mode;

    public Portfolio(String accountId, String accountName, double initialCapital, AccountMode mode) {
        this.accountId = accountId;
        this.accountName = accountName;
        this.initialCapital = initialCapital;
        this.availableCash = initialCapital;
        this.frozenCash = 0;
        this.positions = new HashMap<>();
        this.orderHistory = new ArrayList<>();
        this.closedPositions = new ArrayList<>();
        this.realizedPnl = 0;
        this.createTime = LocalDateTime.now();
        this.lastUpdateTime = LocalDateTime.now();
        this.mode = mode;
    }

    /**
     * 计算总持仓市值
     */
    public double getTotalPositionValue() {
        return positions.values().stream()
                .mapToDouble(Position::getMarketValue)
                .sum();
    }

    /**
     * 计算账户总资产
     */
    public double getTotalAssets() {
        return availableCash + frozenCash + getTotalPositionValue();
    }

    /**
     * 计算总收益（元）= 浮动盈亏 + 已实现盈亏
     */
    public double getTotalProfit() {
        return getTotalAssets() - initialCapital;
    }

    /**
     * 确保 closedPositions 不为 null（反序列化兼容）
     */
    public List<ClosedPosition> getClosedPositions() {
        if (closedPositions == null) closedPositions = new ArrayList<>();
        return closedPositions;
    }

    /**
     * 计算总收益率（%）
     */
    public double getTotalProfitRate() {
        if (initialCapital == 0) return 0;
        return getTotalProfit() / initialCapital * 100;
    }

    /**
     * 计算持仓收益
     */
    public double getPositionProfit() {
        return positions.values().stream()
                .mapToDouble(Position::getProfit)
                .sum();
    }

    /**
     * 获取某支股票持仓
     */
    public Position getPosition(String stockCode) {
        return positions.get(stockCode);
    }

    /**
     * 是否持有该股票
     */
    public boolean hasPosition(String stockCode) {
        Position pos = positions.get(stockCode);
        return pos != null && pos.getQuantity() > 0;
    }

    /**
     * 更新持仓现价（用于计算实时盈亏）
     */
    public void updatePositionPrice(String stockCode, double currentPrice) {
        Position pos = positions.get(stockCode);
        if (pos != null) {
            // 使用 updateCurrentPrice 以同步维护持仓期间最高价（用于追踪止盈）
            pos.updateCurrentPrice(currentPrice);
            pos.setMarketValue(pos.calculateMarketValue());
            pos.setProfit(pos.calculateProfit());
            pos.setProfitRate(pos.calculateProfitRate());
            lastUpdateTime = LocalDateTime.now();
        }
    }

    /**
     * 执行买入（更新资金和持仓）
     */
    public boolean executeBuy(Order order) {
        double totalCost = order.getNetAmount();
        if (availableCash < totalCost) {
            log.warn("资金不足：需要 {}, 可用 {}", totalCost, availableCash);
            return false;
        }

        availableCash -= totalCost;
        orderHistory.add(order);

        Position pos = positions.get(order.getStockCode());
        if (pos == null) {
            pos = Position.builder()
                    .stockCode(order.getStockCode())
                    .stockName(order.getStockName())
                    .quantity(0)
                    .availableQuantity(0)
                    .avgCost(0)
                    .currentPrice(order.getFilledPrice())
                    .firstBuyTime(LocalDateTime.now())
                    .build();
            positions.put(order.getStockCode(), pos);
        }

        pos.addPosition(order.getFilledPrice(), order.getFilledQuantity());
        pos.setCurrentPrice(order.getFilledPrice());
        pos.setMarketValue(pos.calculateMarketValue());
        // T+1：买入当日 availableQuantity 不增加，次日才解锁
        pos.setLastBuyDate(LocalDate.now());
        lastUpdateTime = LocalDateTime.now();

        log.info("买入成功（T+1）：{} {}股 @{}，今日不可卖出，总费用 {}",
                order.getStockCode(), order.getFilledQuantity(),
                String.format("%.2f", order.getFilledPrice()),
                String.format("%.2f", totalCost));
        return true;
    }

    /**
     * T+1 解锁：将昨日及更早买入的持仓设为可用
     * 每天开盘前调用一次
     */
    public void unlockT1Positions() {
        LocalDate today = LocalDate.now();
        positions.values().forEach(pos -> {
            if (pos.getLastBuyDate() != null && pos.getLastBuyDate().isBefore(today)) {
                // 昨日买入，今日解锁全部持仓为可用
                pos.setAvailableQuantity(pos.getQuantity());
                log.info("T+1 解锁：{} {} 股可用", pos.getStockCode(), pos.getQuantity());
            }
        });
    }

    /**
     * 执行卖出（更新资金和持仓）
     */
    public boolean executeSell(Order order) {
        Position pos = positions.get(order.getStockCode());
        if (pos == null || pos.getAvailableQuantity() < order.getFilledQuantity()) {
            log.warn("持仓不足：需要 {}, 可用 {}",
                    order.getFilledQuantity(),
                    pos == null ? 0 : pos.getAvailableQuantity());
            return false;
        }

        double proceeds = order.getNetAmount();
        availableCash += proceeds;
        orderHistory.add(order);

        // 记录已平仓信息（含已实现盈亏）
        double pnl = (order.getFilledPrice() - pos.getAvgCost()) * order.getFilledQuantity() - order.getTotalFee();
        double cost = pos.getAvgCost() * order.getFilledQuantity();
        double pnlRate = cost > 0 ? pnl / cost * 100 : 0;
        ClosedPosition cp = ClosedPosition.builder()
                .stockCode(order.getStockCode())
                .stockName(order.getStockName())
                .quantity(order.getFilledQuantity())
                .avgCost(pos.getAvgCost())
                .sellPrice(order.getFilledPrice())
                .totalFee(order.getTotalFee())
                .realizedPnl(pnl)
                .realizedPnlRate(pnlRate)
                .closeReason(order.getRemark())
                .buyTime(pos.getFirstBuyTime())
                .sellTime(LocalDateTime.now())
                .build();
        if (closedPositions == null) closedPositions = new ArrayList<>();
        closedPositions.add(cp);
        realizedPnl += pnl;

        pos.reducePosition(order.getFilledQuantity());
        if (pos.getQuantity() == 0) {
            positions.remove(order.getStockCode());
        } else {
            pos.setMarketValue(pos.calculateMarketValue());
        }
        lastUpdateTime = LocalDateTime.now();

        log.info("卖出成功：{} {}股 @{}，到账 {}",
                order.getStockCode(), order.getFilledQuantity(),
                String.format("%.2f", order.getFilledPrice()),
                String.format("%.2f", proceeds));
        return true;
    }

    public enum AccountMode {
        SIMULATION("模拟交易"),
        PAPER("纸上交易（历史回测）"),
        LIVE("实盘交易");

        private final String description;

        AccountMode(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}


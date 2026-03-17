package com.stocktrader.strategy;

import com.stocktrader.model.AnalysisResult;
import com.stocktrader.model.Portfolio;
import com.stocktrader.model.StockBar;
import com.stocktrader.model.TradeSignal;

import java.util.List;

/**
 * 交易策略接口
 * 所有具体策略均实现此接口
 */
public interface TradingStrategy {

    /**
     * 获取策略名称
     */
    String getStrategyName();

    /**
     * 获取策略描述
     */
    String getDescription();

    /**
     * 生成交易信号
     * @param stockCode 股票代码
     * @param stockName 股票名称
     * @param bars 历史K线数据（时间升序）
     * @param analysis 技术分析结果（可为null，策略可自行计算）
     * @param portfolio 当前投资组合（用于判断是否已持仓）
     * @return 交易信号，若无信号返回HOLD信号
     */
    TradeSignal generateSignal(String stockCode, String stockName,
                                List<StockBar> bars, AnalysisResult analysis,
                                Portfolio portfolio);

    /**
     * 计算建议仓位比例
     * @param signal 交易信号
     * @param portfolio 投资组合
     * @return 仓位比例 0.0-1.0 （占可用资金的比例）
     */
    double calculatePositionSize(TradeSignal signal, Portfolio portfolio);

    /**
     * 策略是否适用于当前市场环境
     */
    default boolean isApplicable(List<StockBar> bars) {
        return bars != null && bars.size() >= getMinBarsRequired();
    }

    /**
     * 最少需要的K线数量
     */
    int getMinBarsRequired();
}


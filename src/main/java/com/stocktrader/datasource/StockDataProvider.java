package com.stocktrader.datasource;

import com.stocktrader.model.Stock;
import com.stocktrader.model.StockBar;

import java.time.LocalDate;
import java.util.List;

/**
 * 股票数据提供者接口
 * 不同数据源实现此接口（同花顺、东方财富、Yahoo Finance等）
 */
public interface StockDataProvider {

    /**
     * 获取股票基本信息
     * @param stockCode 股票代码
     * @return 股票信息
     */
    Stock getStockInfo(String stockCode);

    /**
     * 批量获取股票基本信息
     * @param stockCodes 股票代码列表
     * @return 股票信息列表
     */
    List<Stock> getStockInfoBatch(List<String> stockCodes);

    /**
     * 获取实时行情
     * @param stockCode 股票代码
     * @return 最新K线数据（包含实时价格）
     */
    StockBar getRealTimeQuote(String stockCode);

    /**
     * 批量获取实时行情
     * @param stockCodes 股票代码列表
     * @return 最新K线数据列表
     */
    List<StockBar> getRealTimeQuoteBatch(List<String> stockCodes);

    /**
     * 获取历史K线数据（日线）
     * @param stockCode 股票代码
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param adjustType 复权类型
     * @return K线列表（按时间升序）
     */
    List<StockBar> getDailyBars(String stockCode, LocalDate startDate, LocalDate endDate,
                                StockBar.AdjustType adjustType);

    /**
     * 获取分钟级K线数据
     * @param stockCode 股票代码
     * @param period K线周期
     * @param count 获取最近N根K线
     * @return K线列表
     */
    List<StockBar> getMinuteBars(String stockCode, StockBar.BarPeriod period, int count);

    /**
     * 获取A股所有股票列表
     * @return 股票列表
     */
    List<Stock> getAllAStocks();

    /**
     * 获取港股股票列表（港股通+港交所主板）
     * 默认实现返回空列表，支持港股的数据源可覆盖此方法。
     * @return 港股列表
     */
    default List<Stock> getAllHkStocks() {
        return new java.util.ArrayList<>();
    }

    /**
     * 搜索股票（支持A股和港股代码/名称关键词）
     * @param keyword 关键词（代码或名称）
     * @return 匹配的股票列表
     */
    List<Stock> searchStock(String keyword);

    /**
     * 获取数据源名称
     */
    String getProviderName();

    /**
     * 检查数据源连接是否正常
     */
    boolean isAvailable();
}


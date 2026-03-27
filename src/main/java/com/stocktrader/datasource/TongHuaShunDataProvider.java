package com.stocktrader.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktrader.model.Stock;
import com.stocktrader.model.StockBar;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 同花顺数据源实现
 * <p>
 * 同花顺提供以下公开数据接口：
 * - 实时行情：https://qt.gtimg.cn/q={code}  (腾讯财经，同花顺也接入)
 * - 历史K线：通过同花顺iFind API（机构版需申请）
 * - 公开实时数据：新浪财经、东方财富API作为备用
 * <p>
 * 本实现采用多数据源融合策略：
 * 1. 优先使用同花顺iFind API（如已配置token）
 * 2. 备用新浪财经实时行情API
 * 3. 备用东方财富历史数据API
 */
public class TongHuaShunDataProvider implements StockDataProvider {

    private static final Logger log = LoggerFactory.getLogger(TongHuaShunDataProvider.class);

    // 新浪财经实时行情（免费公开接口）
    private static final String SINA_REALTIME_URL = "https://hq.sinajs.cn/list=";

    // 东方财富历史K线API（免费公开接口）
    private static final String EMC_KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get";

    // 东方财富实时行情
    private static final String EMC_REALTIME_URL = "https://push2.eastmoney.com/api/qt/stock/get";

    // 同花顺iFind API（需申请token）
    private static final String THS_IFIND_URL = "https://quantapi.51ifind.com/api/v1";

    // Python Tushare 数据服务（本地，稳定可靠）
    private static final String PYTHON_DATA_SERVICE = "http://localhost:8099";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String thsToken;  // 同花顺iFind token（可为空，将使用免费接口）

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public TongHuaShunDataProvider(String thsToken) {
        this.thsToken = thsToken;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public TongHuaShunDataProvider() {
        this(null);
    }

    @Override
    public String getProviderName() {
        return "同花顺/东方财富融合数据源";
    }

    @Override
    public boolean isAvailable() {
        try {
            String testCode = "sh600519";
            StockBar bar = getRealTimeQuote("600519");
            return bar != null;
        } catch (Exception e) {
            log.error("数据源连通性检查失败", e);
            return false;
        }
    }

    @Override
    public StockBar getRealTimeQuote(String stockCode) {
        boolean isHk = "HK".equals(detectExchange(stockCode));
        // 港股大盘指数（如 HSI），直接用东财接口（新浪 hkHSI 格式字段数与个股不同）
        if (isHk && stockCode.matches("[A-Za-z]+")) {
            return getRealTimeQuoteHkIndexByEmc(stockCode);
        }
        String fullCode = buildSinaCode(stockCode);
        String url = SINA_REALTIME_URL + fullCode;
        try {
            String body = httpGet(url);
            return isHk ? parseSinaQuoteHk(stockCode, body) : parseSinaQuote(stockCode, body);
        } catch (Exception e) {
            log.error("获取实时行情失败: {}", stockCode, e);
            return null;
        }
    }

    /**
     * 通过东方财富接口获取港股大盘指数实时行情（如恒生指数 HSI）
     * 东财指数代码映射：HSI -> 116.HSI（需要查询东财的特定格式）
     * 实际东财港股大盘指数 secid: 100.HSI
     */
    private StockBar getRealTimeQuoteHkIndexByEmc(String indexCode) {
        try {
            // 东财港股大盘指数市场代码为 100
            String secId = "100." + indexCode.toUpperCase();
            String url = String.format(
                    "%s?secid=%s&ut=fa5fd1943c7b386f172d6893dbfba10b" +
                    "&fields=f43,f57,f58,f170,f46,f47,f48,f49,f60&invt=2&fltt=2",
                    EMC_REALTIME_URL, secId);
            String body = httpGet(url);
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(body);
            com.fasterxml.jackson.databind.JsonNode d = root.path("data");
            if (d.isMissingNode() || d.isNull()) return null;
            double current   = d.path("f43").asDouble(0) / 1000.0;
            if (current <= 0) return null;
            String name      = d.path("f58").asText(indexCode);
            double prevClose = d.path("f60").asDouble(0) / 1000.0;
            double change    = current - prevClose;
            double changePct = prevClose > 0 ? change / prevClose * 100 : 0;
            return StockBar.builder()
                    .stockCode(indexCode)
                    .stockName(name)
                    .dateTime(LocalDateTime.now())
                    .open(d.path("f46").asDouble(0) / 1000.0)
                    .high(d.path("f44").asDouble(0) / 1000.0)
                    .low(d.path("f45").asDouble(0) / 1000.0)
                    .close(current)
                    .volume(d.path("f47").asLong(0))
                    .amount(d.path("f48").asDouble(0))
                    .change(change)
                    .changePercent(changePct)
                    .period(StockBar.BarPeriod.DAILY)
                    .adjustType(StockBar.AdjustType.NONE)
                    .build();
        } catch (Exception e) {
            log.debug("东财港股大盘指数获取失败: {} ({})", indexCode, e.getMessage());
            return null;
        }
    }

    @Override
    public List<StockBar> getRealTimeQuoteBatch(List<String> stockCodes) {
        List<StockBar> results = new ArrayList<>();
        if (stockCodes == null || stockCodes.isEmpty()) return results;

        // 批量拼接代码
        StringBuilder sb = new StringBuilder();
        for (String code : stockCodes) {
            if (sb.length() > 0) sb.append(",");
            sb.append(buildSinaCode(code));
        }

        String url = SINA_REALTIME_URL + sb;
        try {
            String body = httpGet(url);
            String[] lines = body.split("\n");
            for (int i = 0; i < lines.length && i < stockCodes.size(); i++) {
                String code = stockCodes.get(i);
                boolean isHk = "HK".equals(detectExchange(code));
                StockBar bar = isHk ? parseSinaQuoteHk(code, lines[i]) : parseSinaQuote(code, lines[i]);
                if (bar != null) results.add(bar);
            }
        } catch (Exception e) {
            log.error("批量获取实时行情失败", e);
        }
        return results;
    }

    @Override
    public List<StockBar> getDailyBars(String stockCode, LocalDate startDate, LocalDate endDate,
                                        StockBar.AdjustType adjustType) {
        // 港股走专属方法（支持116/128市场代码回退）
        if ("HK".equals(detectExchange(stockCode))) {
            return getDailyBarsHk(stockCode, startDate, endDate, adjustType);
        }

        // 优先使用 Python Tushare 数据服务（稳定、不限流）
        List<StockBar> bars = getDailyBarsFromPython(stockCode, startDate, endDate, adjustType);
        if (!bars.isEmpty()) {
            return bars;
        }

        // 降级：使用东方财富历史K线API
        log.debug("Python数据服务不可用，降级使用东方财富: {}", stockCode);
        String secId = buildEmcSecId(stockCode);
        int adjFlag = adjustType == StockBar.AdjustType.FORWARD ? 1 :
                (adjustType == StockBar.AdjustType.BACKWARD ? 2 : 0);

        String url = String.format(
                "%s?secid=%s&ut=fa5fd1943c7b386f172d6893dbfba10b&fields1=f1,f2,f3,f4,f5,f6" +
                "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61" +
                "&klt=101&fqt=%d&beg=%s&end=%s&smplmt=500&lmt=1000000",
                EMC_KLINE_URL, secId, adjFlag,
                startDate.format(DATE_FORMAT),
                endDate.format(DATE_FORMAT)
        );

        // 最多重试3次，避免东方财富偶发性连接中断
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String body = httpGet(url);
                List<StockBar> emcBars = parseEmcKlineData(stockCode, body, StockBar.BarPeriod.DAILY, adjustType);
                if (!emcBars.isEmpty()) return emcBars;
                if (attempt < 3) {
                    Thread.sleep(500L * attempt);
                }
            } catch (Exception e) {
                if (attempt == 3) {
                    log.warn("获取历史K线失败(已重试{}次): {} - {}", attempt, stockCode, e.getMessage());
                } else {
                    log.debug("获取历史K线第{}次失败，重试: {} - {}", attempt, stockCode, e.getMessage());
                    try { Thread.sleep(500L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        return new ArrayList<>();
    }

    /**
     * 通过本地 Python Tushare 数据服务获取日K线
     */
    private List<StockBar> getDailyBarsFromPython(String stockCode, LocalDate startDate,
                                                   LocalDate endDate, StockBar.AdjustType adjustType) {
        try {
            String adj = adjustType == StockBar.AdjustType.FORWARD ? "qfq" :
                         adjustType == StockBar.AdjustType.BACKWARD ? "hfq" : "none";
            String url = String.format("%s/kline?code=%s&start=%s&end=%s&adj=%s",
                    PYTHON_DATA_SERVICE, stockCode,
                    startDate.format(DATE_FORMAT),
                    endDate.format(DATE_FORMAT),
                    adj);

            String body = httpGet(url);
            if (body == null || body.trim().isEmpty() || body.trim().startsWith("{\"error")) {
                return new ArrayList<>();
            }

            JsonNode arr = objectMapper.readTree(body);
            if (!arr.isArray() || arr.size() == 0) return new ArrayList<>();

            List<StockBar> bars = new ArrayList<>();
            for (JsonNode item : arr) {
                try {
                    String dateStr = item.path("date").asText();
                    LocalDate date = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
                    StockBar bar = StockBar.builder()
                            .stockCode(stockCode)
                            .dateTime(date.atStartOfDay())
                            .open(item.path("open").asDouble())
                            .high(item.path("high").asDouble())
                            .low(item.path("low").asDouble())
                            .close(item.path("close").asDouble())
                            .volume((long) item.path("volume").asDouble())
                            .amount(item.path("amount").asDouble())
                            .period(StockBar.BarPeriod.DAILY)
                            .adjustType(adjustType)
                            .build();
                    bars.add(bar);
                } catch (Exception e) {
                    log.debug("解析Python K线行失败: {}", item);
                }
            }
            if (!bars.isEmpty()) {
                log.debug("Python数据服务返回: {} {}条K线", stockCode, bars.size());
            }
            return bars;
        } catch (Exception e) {
            // Python服务不可用时静默降级
            log.debug("Python数据服务不可用: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public List<StockBar> getMinuteBars(String stockCode, StockBar.BarPeriod period, int count) {
        String secId = buildEmcSecId(stockCode);
        int klt = periodToEmcKlt(period);

        String url = String.format(
                "%s?secid=%s&ut=fa5fd1943c7b386f172d6893dbfba10b&fields1=f1,f2,f3,f4,f5,f6" +
                "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61" +
                "&klt=%d&fqt=1&lmt=%d&smplmt=%d",
                EMC_KLINE_URL, secId, klt, count, count
        );

        try {
            String body = httpGet(url);
            return parseEmcKlineData(stockCode, body, period, StockBar.AdjustType.FORWARD);
        } catch (Exception e) {
            log.error("获取分钟K线失败: {} {}", stockCode, period, e);
            return new ArrayList<>();
        }
    }

    @Override
    public Stock getStockInfo(String stockCode) {
        List<String> codes = new ArrayList<>();
        codes.add(stockCode);
        List<Stock> list = getStockInfoBatch(codes);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public List<Stock> getStockInfoBatch(List<String> stockCodes) {
        List<Stock> results = new ArrayList<>();
        // 通过实时行情接口获取基本信息（parseSinaQuote 已填充 stockName）
        List<StockBar> quotes = getRealTimeQuoteBatch(stockCodes);
        for (StockBar bar : quotes) {
            Stock stock = Stock.builder()
                    .code(bar.getStockCode())
                    .name(bar.getStockName())
                    .exchange(detectExchange(bar.getStockCode()))
                    .status(Stock.StockStatus.NORMAL)
                    .build();
            results.add(stock);
        }
        return results;
    }

    @Override
    public List<Stock> getAllAStocks() {
        // 东方财富全量股票列表接口
        String url = "https://push2.eastmoney.com/api/qt/clist/get?" +
                "pn=1&pz=5000&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281&fltt=2&invt=2" +
                "&fid=f3&fs=m:0+t:6,m:0+t:13,m:0+t:80,m:1+t:2,m:1+t:23" +
                "&fields=f12,f14,f2,f3&_=1623833739532";
        List<Stock> stocks = new ArrayList<>();
        try {
            String body = httpGet(url);
            JsonNode root = objectMapper.readTree(body);
            JsonNode items = root.path("data").path("diff");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String code = item.path("f12").asText();
                    String name = item.path("f14").asText();
                    Stock stock = Stock.builder()
                            .code(code)
                            .name(name)
                            .exchange(detectExchange(code))
                            .status(Stock.StockStatus.NORMAL)
                            .build();
                    stocks.add(stock);
                }
            }
        } catch (Exception e) {
            log.error("获取全量股票列表失败", e);
        }
        return stocks;
    }

    @Override
    public List<Stock> searchStock(String keyword) {
        String url = "https://suggest3.sinajs.cn/suggest/type=11,12,13,14,15&key=" +
                keyword + "&name=suggestdata_" + System.currentTimeMillis();
        List<Stock> results = new ArrayList<>();
        try {
            String body = httpGet(url);
            // 解析新浪搜索结果格式：code,type,name,...
            if (body.contains("\"")) {
                String data = body.substring(body.indexOf('"') + 1, body.lastIndexOf('"'));
                String[] items = data.split(";");
                for (String item : items) {
                    String[] parts = item.split(",");
                    if (parts.length >= 3) {
                        Stock stock = Stock.builder()
                                .code(parts[0])
                                .name(parts[2])
                                .exchange(detectExchange(parts[0]))
                                .status(Stock.StockStatus.NORMAL)
                                .build();
                        results.add(stock);
                    }
                }
            }
        } catch (Exception e) {
            log.error("搜索股票失败: {}", keyword, e);
        }
        return results;
    }

    // =================== 私有辅助方法 ===================

    /**
     * 解析新浪财经港股实时行情数据
     * 接口格式（rt_hk02015）：
     * var hq_str_rt_hk02015="中国铁建,0.000,50.700,50.700,51.250,50.000,50.650,50.700,
     *   2567688,129812568.000,50.700,30800,50.650,22400,...,2026-03-27,16:01:57,+"
     * 字段顺序（与A股不同）：
     *  [0]  股票名称
     *  [1]  不使用（tariff相关）
     *  [2]  昨收
     *  [3]  今开
     *  [4]  最高
     *  [5]  最低
     *  [6]  买一价
     *  [7]  卖一价
     *  [8]  成交量（手）
     *  [9]  成交额（港元）
     *  [10] 现价
     *  [...买卖五档...]
     *  [倒数3] 日期（yyyy-MM-dd）
     *  [倒数2] 时间（HH:mm:ss）
     *  [倒数1] 涨跌方向（+/-）
     */
    private StockBar parseSinaQuoteHk(String stockCode, String rawData) {
        try {
            int start = rawData.indexOf('"');
            int end = rawData.lastIndexOf('"');
            if (start < 0 || end <= start) return null;
            String data = rawData.substring(start + 1, end);
            if (data.isEmpty()) return null;

            String[] parts = data.split(",");
            if (parts.length < 11) return null;

            String name        = parts[0];
            double prevClose   = Double.parseDouble(parts[2]);  // 昨收
            double open        = Double.parseDouble(parts[3]);  // 今开
            double high        = Double.parseDouble(parts[4]);  // 最高
            double low         = Double.parseDouble(parts[5]);  // 最低
            double current     = Double.parseDouble(parts[10]); // 现价
            long   volume      = (long) Double.parseDouble(parts[8]);  // 成交量（手，已是100股单位）
            double amount      = Double.parseDouble(parts[9]);  // 成交额

            double change        = current - prevClose;
            double changePercent = prevClose > 0 ? change / prevClose * 100 : 0;

            // 时间解析：倒数第3和第2字段
            LocalDateTime dateTime = LocalDateTime.now(); // 默认当前时间（防止解析失败）
            try {
                String dateStr = parts[parts.length - 3];
                String timeStr = parts[parts.length - 2].replace(":", "");
                dateTime = LocalDateTime.parse(dateStr + timeStr,
                        DateTimeFormatter.ofPattern("yyyy-MM-ddHHmmss"));
            } catch (Exception ignored) { /* 时间解析失败时用当前时间 */ }

            return StockBar.builder()
                    .stockCode(stockCode)
                    .stockName(name)
                    .dateTime(dateTime)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(current)
                    .volume(volume)
                    .amount(amount)
                    .change(change)
                    .changePercent(changePercent)
                    .period(StockBar.BarPeriod.DAILY)
                    .adjustType(StockBar.AdjustType.NONE)
                    .build();
        } catch (Exception e) {
            // 港股行情解析失败时降级用东方财富实时接口
            log.debug("新浪港股行情解析失败，尝试东财降级: {} rawData={}", stockCode, rawData);
            return getRealTimeQuoteHkFallback(stockCode);
        }
    }

    /**
     * 港股实时行情降级：通过东方财富实时接口获取
     * 当新浪接口返回格式异常时（非交易时段、停牌等）调用
     */
    private StockBar getRealTimeQuoteHkFallback(String stockCode) {
        try {
            // 港股通用东财 116 市场
            String normalizedCode = String.format("%05d", Integer.parseInt(stockCode));
            String secId = "116." + normalizedCode;
            String url = String.format(
                    "%s?secid=%s&ut=fa5fd1943c7b386f172d6893dbfba10b" +
                    "&fields=f43,f57,f58,f170,f46,f47,f48,f49,f60&invt=2&fltt=2",
                    EMC_REALTIME_URL, secId);
            String body = httpGet(url);
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(body);
            com.fasterxml.jackson.databind.JsonNode d = root.path("data");
            if (d.isMissingNode()) return null;
            double current    = d.path("f43").asDouble(0) / 1000.0;  // 东财价格放大1000倍
            String name       = d.path("f58").asText("");
            double prevClose  = d.path("f60").asDouble(0) / 1000.0;
            double open       = d.path("f46").asDouble(0) / 1000.0;
            double high       = d.path("f44").asDouble(0) / 1000.0;
            double low        = d.path("f45").asDouble(0) / 1000.0;
            long   volume     = d.path("f47").asLong(0);
            double amount     = d.path("f48").asDouble(0);
            double change     = current - prevClose;
            double changePct  = prevClose > 0 ? change / prevClose * 100 : 0;
            if (current <= 0) return null;
            return StockBar.builder()
                    .stockCode(stockCode)
                    .stockName(name.isEmpty() ? stockCode : name)
                    .dateTime(LocalDateTime.now())
                    .open(open).high(high).low(low).close(current)
                    .volume(volume).amount(amount)
                    .change(change).changePercent(changePct)
                    .period(StockBar.BarPeriod.DAILY)
                    .adjustType(StockBar.AdjustType.NONE)
                    .build();
        } catch (Exception e) {
            log.debug("东财港股实时行情降级失败: {}", stockCode);
            return null;
        }
    }

    /**
     * 获取港股股票列表（东方财富港股通/港交所）
     * 东财市场代码：116 = 港股通（沪、深双向），128 = 港交所原生
     */
    public List<Stock> getAllHkStocks() {
        List<Stock> result = new ArrayList<>();
        // 拉取港股通（南向），市场标识 m:128+t:3（港交所主板）
        String url = "https://push2.eastmoney.com/api/qt/clist/get?" +
                "pn=1&pz=3000&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281&fltt=2&invt=2" +
                "&fid=f3&fs=m:128+t:3,m:128+t:4" +
                "&fields=f12,f14,f2,f3&_=" + System.currentTimeMillis();
        try {
            String body = httpGet(url);
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(body);
            com.fasterxml.jackson.databind.JsonNode items = root.path("data").path("diff");
            if (items.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode item : items) {
                    String code = item.path("f12").asText();
                    String name = item.path("f14").asText();
                    if (code.isEmpty() || name.isEmpty()) continue;
                    result.add(Stock.builder()
                            .code(code).name(name)
                            .exchange("HK")
                            .status(Stock.StockStatus.NORMAL)
                            .build());
                }
            }
            log.info("获取港股列表成功，共{}只", result.size());
        } catch (Exception e) {
            log.error("获取港股列表失败", e);
        }
        return result;
    }

    /**
     * 解析新浪财经A股实时行情数据
     * 格式：var hq_str_sh600519="贵州茅台,1720.00,1710.00,1730.00,1745.00,1715.00,1729.98,1730.00,
     *        68163,1189817928.00,..."
     */
    private StockBar parseSinaQuote(String stockCode, String rawData) {
        try {
            int start = rawData.indexOf('"');
            int end = rawData.lastIndexOf('"');
            if (start < 0 || end <= start) return null;

            String data = rawData.substring(start + 1, end);
            if (data.isEmpty()) return null;

            String[] parts = data.split(",");
            if (parts.length < 32) return null;

            String name = parts[0];
            double open = Double.parseDouble(parts[1]);
            double prevClose = Double.parseDouble(parts[2]);
            double current = Double.parseDouble(parts[3]);
            double high = Double.parseDouble(parts[4]);
            double low = Double.parseDouble(parts[5]);
            long volume = Long.parseLong(parts[8]);
            double amount = Double.parseDouble(parts[9]);
            String dateStr = parts[30];
            String timeStr = parts[31];

            double change = current - prevClose;
            double changePercent = prevClose > 0 ? change / prevClose * 100 : 0;

            LocalDateTime dateTime = LocalDateTime.parse(
                    dateStr + timeStr.replace(":", ""),
                    DateTimeFormatter.ofPattern("yyyy-MM-ddHHmmss")
            );

            return StockBar.builder()
                    .stockCode(stockCode)
                    .stockName(name)
                    .dateTime(dateTime)
                    .open(open)
                    .high(high)
                    .low(low)
                    .close(current)
                    .volume(volume)
                    .amount(amount)
                    .change(change)
                    .changePercent(changePercent)
                    .period(StockBar.BarPeriod.DAILY)
                    .adjustType(StockBar.AdjustType.NONE)
                    .build();
        } catch (Exception e) {
            log.debug("解析新浪行情数据失败: {}", rawData, e);
            return null;
        }
    }

    /**
     * 解析东方财富K线数据
     * fields2格式：日期,开盘,收盘,最高,最低,成交量,成交额,振幅,涨跌幅,涨跌额,换手率
     */
    private List<StockBar> parseEmcKlineData(String stockCode, String body,
                                               StockBar.BarPeriod period,
                                               StockBar.AdjustType adjustType) throws IOException {
        List<StockBar> bars = new ArrayList<>();
        JsonNode root = objectMapper.readTree(body);
        JsonNode klines = root.path("data").path("klines");

        if (!klines.isArray()) {
            log.warn("东方财富K线数据为空: {}", stockCode);
            return bars;
        }

        DateTimeFormatter fmt = period == StockBar.BarPeriod.DAILY ||
                period == StockBar.BarPeriod.WEEKLY ||
                period == StockBar.BarPeriod.MONTHLY
                ? DateTimeFormatter.ofPattern("yyyy-MM-dd")
                : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        for (JsonNode kline : klines) {
            String[] parts = kline.asText().split(",");
            if (parts.length < 11) continue;

            try {
                LocalDateTime dt;
                if (parts[0].length() == 10) {
                    dt = LocalDate.parse(parts[0], DateTimeFormatter.ofPattern("yyyy-MM-dd")).atStartOfDay();
                } else {
                    dt = LocalDateTime.parse(parts[0], fmt);
                }

                StockBar bar = StockBar.builder()
                        .stockCode(stockCode)
                        .dateTime(dt)
                        .open(Double.parseDouble(parts[1]))
                        .close(Double.parseDouble(parts[2]))
                        .high(Double.parseDouble(parts[3]))
                        .low(Double.parseDouble(parts[4]))
                        .volume(Long.parseLong(parts[5]))
                        .amount(Double.parseDouble(parts[6]))
                        .changePercent(Double.parseDouble(parts[8]))
                        .change(Double.parseDouble(parts[9]))
                        .turnoverRate(Double.parseDouble(parts[10]))
                        .period(period)
                        .adjustType(adjustType)
                        .build();
                bars.add(bar);
            } catch (Exception e) {
                log.debug("解析K线行失败: {}", kline.asText(), e);
            }
        }
        return bars;
    }

    /**
     * 构建新浪财经股票代码格式
     * 600519 -> sh600519, 000001 -> sz000001
     * 港股: 02015 -> rt_hk02015
     */
    private String buildSinaCode(String code) {
        if (code.startsWith("sh") || code.startsWith("sz") || code.startsWith("bj")
                || code.startsWith("rt_hk") || code.startsWith("hk")) {
            return code;
        }
        // 港股大盘指数：纯字母代码如 HSI、HSCEI -> 直接构建为 hkHSI 格式（新浪接口格式）
        if (code.matches("[A-Za-z]+")) {
            return "hk" + code.toUpperCase();
        }
        String exchange = detectExchange(code);
        if ("HK".equals(exchange)) {
            try {
                // 港股个股补齐5位
                String normalizedCode = String.format("%05d", Integer.parseInt(code));
                return "rt_hk" + normalizedCode;
            } catch (NumberFormatException e) {
                // 港股指数等非数字代码
                return "hk" + code.toUpperCase();
            }
        }
        return exchange.toLowerCase() + code;
    }

    /**
     * 构建东方财富secid
     * sh股票: 1.600519, sz股票: 0.000001
     * 港股: 116.02015 (港股通标的) 或 128.02015 (港交所原生)
     * 东方财富港股市场代码：116 = 港股通（南向）, 128 = 港交所
     */
    private String buildEmcSecId(String code) {
        String exchange = detectExchange(code);
        String market;
        switch (exchange) {
            case "SH": market = "1"; break;
            case "HK":  market = "116"; break;
            default:    market = "0"; break; // SZ / BJ
        }
        // 港股代码补齐为5位
        String normalizedCode = "HK".equals(exchange) ? String.format("%05d", Integer.parseInt(code)) : code;
        return market + "." + normalizedCode;
    }

    /**
     * 根据股票代码判断交易所
     * 港股代码通常为5位数，以0/1/2/3/6/8开头（如02015, 00700, 09988等）
     * 但需与A股区分：A股一般6位，港股一般5位（不足5位补0)
     */
    private String detectExchange(String code) {
        if (code.startsWith("hk") || isHkStock(code)) {
            return "HK";
        } else if (code.startsWith("sh") || code.startsWith("60") || code.startsWith("68") ||
                code.startsWith("900") || code.startsWith("11") || code.startsWith("50") ||
                code.startsWith("51") || code.startsWith("58")) {
            return "SH";
        } else if (code.startsWith("sz") || code.startsWith("00") || code.startsWith("30") ||
                code.startsWith("20") || code.startsWith("12")) {
            return "SZ";
        } else if (code.startsWith("bj") || code.startsWith("8") || code.startsWith("4")) {
            return "BJ";
        }
        // 默认根据首数字判断
        char first = code.charAt(0);
        return (first == '6') ? "SH" : "SZ";
    }

    /**
     * 判断是否为港股代码
     * 港股代码：纯数字，长度 <= 5位（不足5位前补0，如 "2015" 或 "02015"）
     */
    private boolean isHkStock(String code) {
        if (code == null || code.isEmpty()) return false;
        // 纯数字且长度为4或5位视为港股（A股均为6位）
        if (!code.matches("\\d+")) return false;
        return code.length() <= 5;
    }

    /**
     * K线周期转换为东方财富klt参数
     */
    private int periodToEmcKlt(StockBar.BarPeriod period) {
        switch (period) {
            case MIN_1: return 1;
            case MIN_5: return 5;
            case MIN_15: return 15;
            case MIN_30: return 30;
            case MIN_60: return 60;
            case WEEKLY: return 102;
            case MONTHLY: return 103;
            default: return 101; // DAILY
        }
    }

    /**
     * 港股K线回退尝试：先用116（港股通），失败则用128（港交所原生）
     */
    private List<StockBar> getDailyBarsHk(String stockCode, LocalDate startDate, LocalDate endDate,
                                            StockBar.AdjustType adjustType) {
        // ===== 优先通过 Python 数据服务获取港股K线（代理东方财富，绕过直连限制）=====
        List<StockBar> pyBars = getDailyBarsFromPythonHk(stockCode, startDate, endDate, adjustType);
        if (!pyBars.isEmpty()) {
            return pyBars;
        }

        // ===== 降级：直连东方财富港股K线接口 =====
        int adjFlag = adjustType == StockBar.AdjustType.FORWARD ? 1 :
                (adjustType == StockBar.AdjustType.BACKWARD ? 2 : 0);
        String normalizedCode;
        try {
            normalizedCode = String.format("%05d", Integer.parseInt(stockCode.replaceAll("^(hk|HK)", "")));
        } catch (NumberFormatException nfe) {
            log.warn("港股代码格式无效，无法获取K线: {}", stockCode);
            return new ArrayList<>();
        }
        // 尝试 116（港股通南向）和 128（港交所）
        int[] markets = {116, 128};
        for (int market : markets) {
            String secId = market + "." + normalizedCode;
            String url = String.format(
                    "%s?secid=%s&ut=fa5fd1943c7b386f172d6893dbfba10b&fields1=f1,f2,f3,f4,f5,f6" +
                    "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61" +
                    "&klt=101&fqt=%d&beg=%s&end=%s&smplmt=500&lmt=1000000",
                    EMC_KLINE_URL, secId, adjFlag,
                    startDate.format(DATE_FORMAT),
                    endDate.format(DATE_FORMAT)
            );
            try {
                String body = httpGet(url);
                List<StockBar> bars = parseEmcKlineData(stockCode, body, StockBar.BarPeriod.DAILY, adjustType);
                if (!bars.isEmpty()) {
                    log.info("港股{}通过市场{}获取到{}根K线", stockCode, market, bars.size());
                    return bars;
                }
                log.debug("港股{}市场{}无数据，尝试下一个", stockCode, market);
            } catch (Exception e) {
                log.debug("港股{}市场{}请求失败: {}", stockCode, market, e.getMessage());
            }
        }
        log.warn("港股{}所有市场代码均无数据", stockCode);
        return new ArrayList<>();
    }

    /**
     * 通过 Python 数据服务的 /hk_kline 接口获取港股日K线
     * Python 服务已接入东方财富港股行情，并做了缓存和错误处理
     */
    private List<StockBar> getDailyBarsFromPythonHk(String stockCode, LocalDate startDate,
                                                     LocalDate endDate, StockBar.AdjustType adjustType) {
        try {
            String adj = adjustType == StockBar.AdjustType.FORWARD ? "qfq" :
                         adjustType == StockBar.AdjustType.BACKWARD ? "hfq" : "none";
            String url = String.format("%s/hk_kline?code=%s&start=%s&end=%s&adj=%s",
                    PYTHON_DATA_SERVICE, stockCode,
                    startDate.format(DATE_FORMAT),
                    endDate.format(DATE_FORMAT),
                    adj);

            String body = httpGet(url);
            if (body == null || body.trim().isEmpty() || body.trim().startsWith("{\"error")) {
                return new ArrayList<>();
            }

            JsonNode arr = objectMapper.readTree(body);
            if (!arr.isArray() || arr.size() == 0) return new ArrayList<>();

            List<StockBar> bars = new ArrayList<>();
            for (JsonNode item : arr) {
                try {
                    String dateStr = item.path("date").asText();
                    LocalDate date = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
                    StockBar bar = StockBar.builder()
                            .stockCode(stockCode)
                            .dateTime(date.atStartOfDay())
                            .open(item.path("open").asDouble())
                            .high(item.path("high").asDouble())
                            .low(item.path("low").asDouble())
                            .close(item.path("close").asDouble())
                            .volume((long) item.path("volume").asDouble())
                            .amount(item.path("amount").asDouble())
                            .period(StockBar.BarPeriod.DAILY)
                            .adjustType(adjustType)
                            .build();
                    bars.add(bar);
                } catch (Exception e) {
                    log.debug("解析Python港股K线行失败: {}", item);
                }
            }
            if (!bars.isEmpty()) {
                log.debug("[港股K线] Python数据服务返回: {} {}条K线", stockCode, bars.size());
            }
            return bars;
        } catch (Exception e) {
            log.debug("[港股K线] Python数据服务不可用: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 执行HTTP GET请求（自动处理新浪GB18030编码）
     */
    private String httpGet(String url) throws IOException {
        return httpGet(url, null);
    }

    /**
     * 执行HTTP GET请求，支持指定编码
     */
    private String httpGet(String url, Charset forceCharset) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://finance.sina.com.cn/")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP请求失败: " + response.code() + " " + url);
            }
            if (response.body() == null) return "";
            // 优先使用指定编码，其次从 Content-Type 头解析，最后默认 UTF-8
            Charset charset = forceCharset;
            if (charset == null) {
                String contentType = response.header("Content-Type", "");
                if (contentType != null && contentType.toLowerCase().contains("gb")) {
                    try {
                        String cs = contentType.replaceAll(".*charset=([^;\\s]+).*", "$1").trim();
                        charset = Charset.forName(cs);
                    } catch (Exception e) {
                        charset = Charset.forName("GB18030");
                    }
                }
            }
            byte[] bytes = response.body().bytes();
            return charset != null ? new String(bytes, charset) : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}


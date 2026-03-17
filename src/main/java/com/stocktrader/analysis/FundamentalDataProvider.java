package com.stocktrader.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktrader.model.FundamentalFactor;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基本面因子数据提供器
 * <p>
 * 通过 HTTP 调用本地 Python Tushare 服务获取：
 *   GET  /fundamental        - 单股基本面因子
 *   POST /fundamental_batch  - 批量基本面因子（用于IC检验）
 * <p>
 * Tushare 积分要求：
 *   daily_basic  - 每日指标（PE/PB/PS/市值）：需要 2000+ 积分
 *   fina_indicator - 财务指标（ROE/ROA）：需要 5000+ 积分
 * <p>
 * 如积分不足，接口会返回0值（不会报错），IC检验将跳过无效数据。
 */
public class FundamentalDataProvider {

    private static final Logger log = LoggerFactory.getLogger(FundamentalDataProvider.class);

    private static final String BASE_URL = "http://localhost:8099";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper jsonMapper;

    /** 内存缓存（code -> factor，有效期1小时）*/
    private final Map<String, long[]> cacheTimestamps = new LinkedHashMap<>();
    private final Map<String, FundamentalFactor> cache = new LinkedHashMap<>();
    private static final long CACHE_TTL_MS = 3600_000L; // 1小时

    public FundamentalDataProvider() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        this.jsonMapper = new ObjectMapper();
    }

    // ============================= 单股获取 =============================

    /**
     * 获取单股基本面因子
     * @param stockCode 股票代码（如 000547）
     * @param stockName 股票名称
     * @return 基本面因子，失败时返回空对象（所有数值为0）
     */
    public FundamentalFactor getFundamental(String stockCode, String stockName) {
        // 缓存检查
        FundamentalFactor cached = getFromCache(stockCode);
        if (cached != null) return cached;

        try {
            String url = BASE_URL + "/fundamental?code=" + stockCode;
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("基本面接口异常 code={} status={}", stockCode, resp.code());
                    return emptyFactor(stockCode, stockName);
                }
                String body = resp.body().string();
                FundamentalFactor factor = parseJson(body, stockCode, stockName);
                putToCache(stockCode, factor);
                return factor;
            }
        } catch (Exception e) {
            log.warn("获取基本面因子失败 code={}: {}", stockCode, e.getMessage());
            return emptyFactor(stockCode, stockName);
        }
    }

    // ============================= 批量获取 =============================

    /**
     * 批量获取基本面因子（用于IC检验或批量筛选）
     * @param stockCodes  股票代码列表
     * @param stockNames  代码->名称映射
     * @return code -> FundamentalFactor
     */
    public Map<String, FundamentalFactor> getBatchFundamental(
            List<String> stockCodes, Map<String, String> stockNames) {
        Map<String, FundamentalFactor> result = new LinkedHashMap<>();
        List<String> needFetch = new ArrayList<>();

        for (String code : stockCodes) {
            FundamentalFactor cached = getFromCache(code);
            if (cached != null) { result.put(code, cached); }
            else { needFetch.add(code); }
        }

        if (needFetch.isEmpty()) return result;

        try {
            // 构造POST body
            StringBuilder sb = new StringBuilder("{\"codes\":[");
            for (int i = 0; i < needFetch.size(); i++) {
                sb.append("\"").append(needFetch.get(i)).append("\"");
                if (i < needFetch.size() - 1) sb.append(",");
            }
            sb.append("]}");

            RequestBody body = RequestBody.create(sb.toString(), JSON);
            Request req = new Request.Builder()
                    .url(BASE_URL + "/fundamental_batch")
                    .post(body).build();

            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("批量基本面接口异常 status={}", resp.code());
                    needFetch.forEach(c -> result.put(c, emptyFactor(c, stockNames.getOrDefault(c, c))));
                    return result;
                }
                String respBody = resp.body().string();
                JsonNode arr = jsonMapper.readTree(respBody);
                if (arr.isArray()) {
                    for (JsonNode node : arr) {
                        String code = node.path("code").asText();
                        String name = stockNames.getOrDefault(code, code);
                        FundamentalFactor factor = parseJsonNode(node, code, name);
                        result.put(code, factor);
                        putToCache(code, factor);
                    }
                }
                // 没有返回的补空
                needFetch.stream()
                        .filter(c -> !result.containsKey(c))
                        .forEach(c -> result.put(c, emptyFactor(c, stockNames.getOrDefault(c, c))));
            }
        } catch (Exception e) {
            log.warn("批量基本面获取失败: {}", e.getMessage());
            needFetch.forEach(c -> result.put(c, emptyFactor(c, stockNames.getOrDefault(c, c))));
        }

        return result;
    }

    // ============================= 辅助方法 =============================

    private FundamentalFactor parseJson(String json, String code, String name) throws Exception {
        JsonNode node = jsonMapper.readTree(json);
        return parseJsonNode(node, code, name);
    }

    private FundamentalFactor parseJsonNode(JsonNode node, String code, String name) {
        return FundamentalFactor.builder()
                .stockCode(code)
                .stockName(name)
                .dataDate(LocalDate.now())
                .peTtm(node.path("pe_ttm").asDouble(0))
                .pb(node.path("pb").asDouble(0))
                .psTtm(node.path("ps_ttm").asDouble(0))
                .dvRatio(node.path("dv_ratio").asDouble(0))
                .totalMv(node.path("total_mv").asDouble(0))
                .circMv(node.path("circ_mv").asDouble(0))
                .roe(node.path("roe").asDouble(0))
                .roa(node.path("roa").asDouble(0))
                .grossMargin(node.path("gross_margin").asDouble(0))
                .revenueYoy(node.path("revenue_yoy").asDouble(0))
                .profitYoy(node.path("profit_yoy").asDouble(0))
                .debtRatio(node.path("debt_ratio").asDouble(0))
                .reportPeriod(node.path("report_period").asText(""))
                .fundamentalScore(node.path("fundamental_score").asInt(0))
                .build();
    }

    private FundamentalFactor emptyFactor(String code, String name) {
        return FundamentalFactor.builder()
                .stockCode(code).stockName(name)
                .dataDate(LocalDate.now())
                .build();
    }

    private FundamentalFactor getFromCache(String code) {
        long[] ts = cacheTimestamps.get(code);
        if (ts != null && System.currentTimeMillis() - ts[0] < CACHE_TTL_MS) {
            return cache.get(code);
        }
        return null;
    }

    private void putToCache(String code, FundamentalFactor factor) {
        cache.put(code, factor);
        cacheTimestamps.put(code, new long[]{System.currentTimeMillis()});
    }
}


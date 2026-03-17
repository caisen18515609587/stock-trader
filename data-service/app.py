#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
股票数据服务 - Tushare Pro 数据中间层
为 Java 交易系统提供稳定的 K 线、实时行情、股票列表等数据接口

启动方式: python3 app.py
默认端口: 8099
"""

import warnings
warnings.filterwarnings('ignore')

import os
import json
import time
import logging
import threading
from collections import OrderedDict
from datetime import datetime, timedelta
from functools import lru_cache

import tushare as ts
import pandas as pd
from flask import Flask, jsonify, request

# ========== 配置 ==========
TUSHARE_TOKEN = '0922c8671d0cef4c2037f12fa2bf199172de51af22f8c7d386629888'
SERVICE_PORT  = 8099
LOG_LEVEL     = logging.INFO

# ========== 初始化 ==========
logging.basicConfig(
    level=LOG_LEVEL,
    format='%(asctime)s [%(levelname)s] %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
log = logging.getLogger(__name__)

ts.set_token(TUSHARE_TOKEN)
pro = ts.pro_api()

app = Flask(__name__)

# ========== [P1-3 优化] LRU+TTL 缓存，防止无限制增长导致 OOM ==========
# 容量上限：最多缓存 2000 条（超过时自动淘汰最久未访问的条目）
# TTL 策略：行情数据5分钟，基础信息24小时
CACHE_MAX_SIZE   = 2000      # LRU 最大条目数
CACHE_TTL_SHORT  = 300       # 5分钟（行情数据）
CACHE_TTL_LONG   = 86400     # 24小时（股票基本信息，Tushare 限每小时1次）

class LRUTTLCache:
    """线程安全的 LRU+TTL 缓存"""
    def __init__(self, maxsize=CACHE_MAX_SIZE):
        self._cache = OrderedDict()
        self._maxsize = maxsize
        self._lock = threading.Lock()
        self._hits = 0
        self._misses = 0

    def get(self, key):
        with self._lock:
            item = self._cache.get(key)
            if item is None:
                self._misses += 1
                return None
            if time.time() - item['ts'] >= item['ttl']:
                # 已过期，惰性删除
                del self._cache[key]
                self._misses += 1
                return None
            # LRU：将访问的条目移到末尾（最近使用）
            self._cache.move_to_end(key)
            self._hits += 1
            return item['data']

    def set(self, key, data, ttl=CACHE_TTL_SHORT):
        with self._lock:
            if key in self._cache:
                self._cache.move_to_end(key)
            self._cache[key] = {'data': data, 'ts': time.time(), 'ttl': ttl}
            # 超过容量时，淘汰最久未使用的（OrderedDict 头部）
            while len(self._cache) > self._maxsize:
                evicted_key, _ = self._cache.popitem(last=False)
                log.debug(f"[LRU缓存] 淘汰过期/最久未使用条目: {evicted_key}")

    def stats(self):
        with self._lock:
            total = self._hits + self._misses
            hit_rate = self._hits / total * 100 if total > 0 else 0
            return {
                'size': len(self._cache),
                'maxsize': self._maxsize,
                'hits': self._hits,
                'misses': self._misses,
                'hit_rate': f'{hit_rate:.1f}%'
            }

    def clear(self):
        with self._lock:
            self._cache.clear()

_lru_cache = LRUTTLCache(maxsize=CACHE_MAX_SIZE)

def cache_get(key):
    return _lru_cache.get(key)

def cache_set(key, data, ttl=None):
    _lru_cache.set(key, data, ttl=ttl or CACHE_TTL_SHORT)


# ========== 工具函数 ==========

def code_to_ts(code):
    """将代码转为 Tushare 格式: 600519 -> 600519.SH, 000001 -> 000001.SZ"""
    if '.' in code:
        return code
    if code.startswith('6') or code.startswith('5'):
        return code + '.SH'
    elif code.startswith('8') or code.startswith('4'):
        return code + '.BJ'
    else:
        return code + '.SZ'

def safe_float(val):
    """安全转为float，NaN转为0"""
    try:
        f = float(val)
        return 0.0 if (f != f) else f  # NaN check
    except:
        return 0.0

def safe_int(val):
    try:
        return int(val)
    except:
        return 0


# ========== API 接口 ==========

@app.route('/health', methods=['GET'])
def health():
    """健康检查（含缓存统计）"""
    return jsonify({
        'status': 'ok',
        'time': datetime.now().strftime('%Y-%m-%d %H:%M:%S'),
        'cache': _lru_cache.stats()  # [P1-3] 暴露LRU缓存命中率，便于运维监控
    })


@app.route('/kline', methods=['GET'])
def get_kline():
    """
    获取日K线数据（前复权）
    参数: code=000547, start=20250101, end=20260302
    返回: [{date, open, high, low, close, volume, amount}, ...]  按日期升序
    """
    code      = request.args.get('code', '')
    start     = request.args.get('start', (datetime.now() - timedelta(days=365)).strftime('%Y%m%d'))
    end       = request.args.get('end', datetime.now().strftime('%Y%m%d'))
    adj       = request.args.get('adj', 'qfq')  # 前复权

    if not code:
        return jsonify({'error': '缺少code参数'}), 400

    cache_key = f'kline_{code}_{start}_{end}_{adj}'
    cached = cache_get(cache_key)
    if cached:
        return jsonify(cached)

    try:
        ts_code = code_to_ts(code)
        df = pro.daily(ts_code=ts_code, start_date=start, end_date=end)

        if df is None or df.empty:
            log.warning(f'K线无数据: {code}')
            return jsonify([])

        # 获取复权因子
        if adj in ('qfq', 'hfq'):
            try:
                adj_type = 'qfq' if adj == 'qfq' else 'hfq'
                adj_df = pro.adj_factor(ts_code=ts_code, start_date=start, end_date=end)
                if adj_df is not None and not adj_df.empty:
                    df = df.merge(adj_df[['trade_date', 'adj_factor']], on='trade_date', how='left')
                    df['adj_factor'] = df['adj_factor'].fillna(1.0)
                    # 前复权：以最新日期为基准
                    latest_factor = df['adj_factor'].iloc[0]
                    df['adj_factor'] = df['adj_factor'] / latest_factor
                    for col in ['open', 'high', 'low', 'close', 'pre_close']:
                        if col in df.columns:
                            df[col] = df[col] * df['adj_factor']
            except Exception as e:
                log.warning(f'复权因子获取失败，使用不复权数据: {e}')

        # 按日期升序排列
        df = df.sort_values('trade_date', ascending=True)

        result = []
        for _, row in df.iterrows():
            result.append({
                'date':   row['trade_date'],
                'open':   safe_float(row['open']),
                'high':   safe_float(row['high']),
                'low':    safe_float(row['low']),
                'close':  safe_float(row['close']),
                'volume': safe_float(row.get('vol', 0)),
                'amount': safe_float(row.get('amount', 0)),
            })

        cache_set(cache_key, result)
        log.info(f'K线返回: {code} {len(result)}条 [{start}~{end}]')
        return jsonify(result)

    except Exception as e:
        log.error(f'K线获取失败 {code}: {e}')
        return jsonify({'error': str(e)}), 500


@app.route('/realtime', methods=['GET'])
def get_realtime():
    """
    获取实时行情（优先东方财富盘中实时数据，备用 Tushare 日线收盘价）
    参数: code=000547
    返回: {code, name, price, open, high, low, preClose, volume, amount, changePercent, date, isRealtime}
    isRealtime=true 表示盘中实时价，false 表示日线收盘价
    """
    code = request.args.get('code', '')
    if not code:
        return jsonify({'error': '缺少code参数'}), 400

    # ---- 优先：东方财富盘中实时行情（免费公开接口，支持盘中价）----
    try:
        result = _get_realtime_from_eastmoney(code)
        if result:
            log.info(f'实时行情(东方财富盘中): {code} 价格={result["price"]} 涨跌={result["changePercent"]:.2f}%')
            return jsonify(result)
    except Exception as e:
        log.warning(f'东方财富实时行情失败 {code}: {e}，降级使用Tushare日线')

    # ---- 备用：Tushare 日线收盘价（非交易时间 / 东方财富超时时使用）----
    try:
        ts_code = code_to_ts(code)
        end   = datetime.now().strftime('%Y%m%d')
        start = (datetime.now() - timedelta(days=7)).strftime('%Y%m%d')
        df = pro.daily(ts_code=ts_code, start_date=start, end_date=end)

        if df is None or df.empty:
            return jsonify({'error': f'无数据: {code}'}), 404

        row = df.iloc[0]  # 最新一条（降序）
        close     = safe_float(row['close'])
        pre_close = safe_float(row['pre_close'])
        change_pct = ((close - pre_close) / pre_close * 100) if pre_close > 0 else 0.0

        result = {
            'code':          code,
            'name':          '',
            'price':         close,
            'open':          safe_float(row['open']),
            'high':          safe_float(row['high']),
            'low':           safe_float(row['low']),
            'preClose':      pre_close,
            'volume':        safe_float(row.get('vol', 0)),
            'amount':        safe_float(row.get('amount', 0)),
            'changePercent': round(change_pct, 4),
            'date':          row['trade_date'],
            'isRealtime':    False,   # 标记：非实时，为日线收盘价
        }
        log.info(f'实时行情(Tushare日线备用): {code} 价格={close} 日期={row["trade_date"]}')
        return jsonify(result)

    except Exception as e:
        log.error(f'实时行情失败 {code}: {e}')
        return jsonify({'error': str(e)}), 500


def _get_realtime_from_eastmoney(code):
    """
    从东方财富获取盘中实时行情（免费公开接口）
    接口文档：https://push2.eastmoney.com/api/qt/stock/get
    fields 说明：
      f43=最新价(×100)  f44=最高价(×100)  f45=最低价(×100)
      f46=今开(×100)    f60=昨收(×100)    f47=成交量(手)
      f48=成交额        f169=涨跌额(×100) f170=涨跌幅(×1000)
      f14=股票名称      f57=代码
    """
    import urllib.request

    # 构建东方财富 secid
    if code.startswith('6') or code.startswith('5'):
        secid = f'1.{code}'
    elif code.startswith('8') or code.startswith('4'):
        secid = f'0.{code}'  # 北交所
    else:
        secid = f'0.{code}'  # 深市

    url = (
        f'https://push2.eastmoney.com/api/qt/stock/get'
        f'?secid={secid}'
        f'&ut=fa5fd1943c7b386f172d6893dbfba10b'
        f'&fields=f43,f44,f45,f46,f47,f48,f60,f169,f170,f14,f57,f58'
        f'&invt=2&fltt=2'
    )

    req = urllib.request.Request(url, headers={
        'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) '
                      'AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://finance.eastmoney.com/',
    })
    with urllib.request.urlopen(req, timeout=5) as resp:
        data = json.loads(resp.read().decode('utf-8'))

    d = data.get('data') or {}
    price = safe_float(d.get('f43', 0))
    if price <= 0:
        return None  # 无效数据（非交易时间或停牌）

    # 东方财富返回的价格是原始值×100（整数），需除以100
    # 但部分接口 fltt=2 时已是浮点，需判断
    def ef(val, divisor=1):
        v = safe_float(val)
        # 东方财富某些字段返回 "-" 或 0 时表示无效
        if v == 0 or v == '-':
            return 0.0
        return v / divisor if v > 1000 and divisor > 1 else v

    price      = safe_float(d.get('f43', 0))
    high       = safe_float(d.get('f44', 0))
    low        = safe_float(d.get('f45', 0))
    open_price = safe_float(d.get('f46', 0))
    pre_close  = safe_float(d.get('f60', 0))
    volume     = safe_float(d.get('f47', 0))   # 单位：手
    amount     = safe_float(d.get('f48', 0))   # 单位：元
    change_pct = safe_float(d.get('f170', 0))  # 涨跌幅 %（已是百分比）
    name       = str(d.get('f14', ''))
    trade_date = datetime.now().strftime('%Y%m%d')

    if price <= 0:
        return None

    return {
        'code':          code,
        'name':          name,
        'price':         price,
        'open':          open_price,
        'high':          high,
        'low':           low,
        'preClose':      pre_close,
        'volume':        volume * 100,  # 手 → 股
        'amount':        amount,
        'changePercent': round(change_pct, 4),
        'date':          trade_date,
        'isRealtime':    True,   # 标记：真实盘中价
    }


@app.route('/stock_list', methods=['GET'])
def get_stock_list():
    """
    获取全量A股股票列表（含名称、市场）
    返回: [{code, name, market, listDate}, ...]
    """
    cache_key = 'stock_list'
    cached = cache_get(cache_key)
    if cached:
        return jsonify(cached)

    try:
        df = pro.stock_basic(exchange='', list_status='L',
                             fields='ts_code,symbol,name,area,industry,market,list_date')
        if df is None or df.empty:
            return jsonify([])

        result = []
        for _, row in df.iterrows():
            result.append({
                'code':     row['symbol'],
                'tsCode':   row['ts_code'],
                'name':     row['name'],
                'market':   row.get('market', ''),
                'industry': row.get('industry', ''),
                'listDate': row.get('list_date', ''),
            })

        cache_set(cache_key, result)
        log.info(f'股票列表返回: {len(result)}只')
        return jsonify(result)

    except Exception as e:
        log.error(f'股票列表失败: {e}')
        return jsonify({'error': str(e)}), 500


@app.route('/batch_realtime', methods=['GET'])
def get_batch_realtime():
    """
    批量获取实时行情（用于选股快照）
    参数: codes=000547,000001,600519（逗号分隔，最多50个）
    """
    codes_str = request.args.get('codes', '')
    if not codes_str:
        return jsonify({'error': '缺少codes参数'}), 400

    codes = [c.strip() for c in codes_str.split(',') if c.strip()][:50]

    try:
        end   = datetime.now().strftime('%Y%m%d')
        start = (datetime.now() - timedelta(days=7)).strftime('%Y%m%d')

        result = {}
        for code in codes:
            try:
                ts_code = code_to_ts(code)
                df = pro.daily(ts_code=ts_code, start_date=start, end_date=end)
                if df is not None and not df.empty:
                    row = df.iloc[0]
                    close = safe_float(row['close'])
                    pre   = safe_float(row['pre_close'])
                    result[code] = {
                        'price':         close,
                        'changePercent': round((close - pre) / pre * 100, 4) if pre > 0 else 0,
                        'volume':        safe_float(row.get('vol', 0)),
                        'date':          row['trade_date'],
                    }
                time.sleep(0.05)  # 避免触发频率限制
            except Exception as e:
                log.warning(f'批量行情 {code} 失败: {e}')

        return jsonify(result)

    except Exception as e:
        log.error(f'批量行情失败: {e}')
        return jsonify({'error': str(e)}), 500


@app.route('/market_daily', methods=['GET'])
def get_market_daily():
    """
    【核心接口】一次请求获取全市场当日行情快照（替代逐只请求）
    参数: date=20260302（不传则取最近交易日）
    返回: [{code, name, price, open, high, low, preClose, changePercent,
            volume, amount, turnoverRate, pe, totalMarketCap}, ...]
    只消耗 1 次调用频率！
    """
    trade_date = request.args.get('date', '')

    # 缓存key：按日期缓存，盘中数据5分钟刷新一次
    cache_key = f'market_daily_{trade_date or "latest"}'
    cached = cache_get(cache_key)
    if cached:
        log.info(f'市场行情命中缓存: {len(cached)}只')
        return jsonify(cached)

    try:
        # 1. 一次性获取全市场日线行情（仅需 1 次调用）
        if trade_date:
            df_daily = pro.daily(trade_date=trade_date)
        else:
            # 不传日期则取最近交易日（Tushare 最多返回最近1天）
            df_daily = pro.daily(trade_date=datetime.now().strftime('%Y%m%d'))
            if df_daily is None or df_daily.empty:
                # 今天可能还未收盘更新，取昨天
                yesterday = (datetime.now() - timedelta(days=1)).strftime('%Y%m%d')
                df_daily = pro.daily(trade_date=yesterday)
                trade_date = yesterday

        if df_daily is None or df_daily.empty:
            log.warning('市场日线数据为空')
            return jsonify([])

        actual_date = df_daily['trade_date'].iloc[0] if not df_daily.empty else trade_date
        log.info(f'Tushare daily 获取全市场行情: {actual_date}，共 {len(df_daily)} 只')

        # 2. 获取股票基本信息（名称、行业等），缓存24小时（Tushare stock_basic 限每小时1次）
        basic_cache_key = 'stock_basic_map'
        basic_map = cache_get(basic_cache_key)
        if basic_map is None:
            try:
                df_basic = pro.stock_basic(exchange='', list_status='L',
                                           fields='ts_code,symbol,name,industry,market')
                basic_map = {}
                if df_basic is not None and not df_basic.empty:
                    for _, row in df_basic.iterrows():
                        basic_map[row['ts_code']] = {
                            'name':     row['name'],
                            'industry': row.get('industry', ''),
                            'market':   row.get('market', ''),
                        }
                cache_set(basic_cache_key, basic_map, ttl=CACHE_TTL_LONG)  # 24小时
                log.info(f'股票基本信息已缓存: {len(basic_map)} 只（24小时有效）')
            except Exception as e:
                log.warning(f'stock_basic 获取失败（频率限制），使用空名称降级: {e}')
                basic_map = {}  # 降级：名称留空，不影响行情数据返回

        # 3. 获取每日指标（PE、换手率、总市值）—— 需要 2000 积分，120积分跳过
        df_basic_daily = None
        # 暂不调用 daily_basic（需要2000积分），PE和换手率设为0

        # 4. 组装结果
        result = []
        for _, row in df_daily.iterrows():
            ts_code = row['ts_code']
            symbol  = ts_code.split('.')[0]  # 去掉 .SH/.SZ 后缀

            # 跳过非正常股票（ST、退市等通过名称过滤）
            info = basic_map.get(ts_code, {})
            name = info.get('name', symbol)
            if 'ST' in name or '退' in name:
                continue

            close     = safe_float(row['close'])
            pre_close = safe_float(row['pre_close'])
            if close <= 0 or pre_close <= 0:
                continue

            change_pct = round((close - pre_close) / pre_close * 100, 4)

            result.append({
                'code':          symbol,
                'tsCode':        ts_code,
                'name':          name,
                'industry':      info.get('industry', ''),
                'price':         close,
                'open':          safe_float(row['open']),
                'high':          safe_float(row['high']),
                'low':           safe_float(row['low']),
                'preClose':      pre_close,
                'changePercent': change_pct,
                'change':        round(close - pre_close, 4),
                'volume':        safe_float(row.get('vol', 0)),
                'amount':        safe_float(row.get('amount', 0)),
                # 以下字段 120 积分不提供，置为 0（预筛选时忽略这些条件）
                'turnoverRate':  0.0,
                'pe':            0.0,
                'totalMarketCap': 0.0,
            })

        # 按涨幅降序排列（方便预筛选）
        result.sort(key=lambda x: x['changePercent'], reverse=True)

        cache_set(cache_key, result)
        log.info(f'市场行情返回: {actual_date} 共 {len(result)} 只（过滤ST/退市后）')
        return jsonify(result)

    except Exception as e:
        log.error(f'市场行情失败: {e}')
        return jsonify({'error': str(e)}), 500


@app.route('/fundamental', methods=['GET'])
def get_fundamental():
    """
    获取股票基本面因子（用于量化选股和 IC 检验）
    参数: code=000547（必填）
    返回:
      pe_ttm       - 市盈率(TTM)
      pb           - 市净率
      ps_ttm       - 市销率(TTM)
      dv_ratio     - 股息率(%)
      total_mv     - 总市值(万元)
      circ_mv      - 流通市值(万元)
      roe          - 净资产收益率(%)（最新一期财报）
      roa          - 总资产收益率(%)
      gross_margin - 毛利率(%)
      revenue_yoy  - 营收同比增速(%)
      profit_yoy   - 净利润同比增速(%)
      debt_ratio   - 资产负债率(%)
    需要 Tushare 2000+ 积分
    """
    code = request.args.get('code', '')
    if not code:
        return jsonify({'error': '缺少code参数'}), 400

    cache_key = f'fundamental_{code}'
    cached = cache_get(cache_key)
    if cached:
        return jsonify(cached)

    ts_code = code_to_ts(code)
    result = {'code': code, 'ts_code': ts_code}

    try:
        # 1. 每日指标（PE/PB/PS/市值/股息率）
        today = datetime.now().strftime('%Y%m%d')
        daily_basic = pro.daily_basic(ts_code=ts_code, trade_date=today,
                                       fields='ts_code,trade_date,pe_ttm,pb,ps_ttm,dv_ratio,total_mv,circ_mv')
        if daily_basic is not None and not daily_basic.empty:
            row = daily_basic.iloc[0]
            result['pe_ttm']   = safe_float(row.get('pe_ttm', 0))
            result['pb']       = safe_float(row.get('pb', 0))
            result['ps_ttm']   = safe_float(row.get('ps_ttm', 0))
            result['dv_ratio'] = safe_float(row.get('dv_ratio', 0))
            result['total_mv'] = safe_float(row.get('total_mv', 0))
            result['circ_mv']  = safe_float(row.get('circ_mv', 0))
        else:
            result.update({'pe_ttm': 0, 'pb': 0, 'ps_ttm': 0, 'dv_ratio': 0,
                           'total_mv': 0, 'circ_mv': 0})
    except Exception as e:
        log.warning(f'每日指标获取失败({code}): {e}')
        result.update({'pe_ttm': 0, 'pb': 0, 'ps_ttm': 0, 'dv_ratio': 0,
                       'total_mv': 0, 'circ_mv': 0})

    try:
        # 2. 财务指标（ROE/ROA/毛利率/营收增速/利润增速/负债率）
        # 取最近2期报告期，选最新一期
        fin = pro.fina_indicator(ts_code=ts_code, limit=2,
                                  fields='ts_code,ann_date,period,roe,roa,grossprofit_margin,'
                                         'or_yoy,netprofit_yoy,debt_to_assets')
        if fin is not None and not fin.empty:
            row = fin.iloc[0]
            result['roe']          = safe_float(row.get('roe', 0))
            result['roa']          = safe_float(row.get('roa', 0))
            result['gross_margin'] = safe_float(row.get('grossprofit_margin', 0))
            result['revenue_yoy']  = safe_float(row.get('or_yoy', 0))
            result['profit_yoy']   = safe_float(row.get('netprofit_yoy', 0))
            result['debt_ratio']   = safe_float(row.get('debt_to_assets', 0))
            result['report_period'] = str(row.get('period', ''))
        else:
            result.update({'roe': 0, 'roa': 0, 'gross_margin': 0,
                           'revenue_yoy': 0, 'profit_yoy': 0, 'debt_ratio': 0,
                           'report_period': ''})
    except Exception as e:
        log.warning(f'财务指标获取失败({code}): {e}')
        result.update({'roe': 0, 'roa': 0, 'gross_margin': 0,
                       'revenue_yoy': 0, 'profit_yoy': 0, 'debt_ratio': 0,
                       'report_period': ''})

    # 综合评分（简单加权）
    score = 0
    pe = result.get('pe_ttm', 0)
    pb = result.get('pb', 0)
    roe = result.get('roe', 0)
    profit_yoy = result.get('profit_yoy', 0)
    dv = result.get('dv_ratio', 0)
    if 0 < pe < 20: score += 30
    elif 20 <= pe < 40: score += 15
    if pb < 3: score += 20
    elif pb < 6: score += 10
    if roe > 15: score += 25
    elif roe > 8: score += 12
    if profit_yoy > 20: score += 15
    elif profit_yoy > 0: score += 5
    if dv > 2: score += 10
    result['fundamental_score'] = min(score, 100)

    cache_set(cache_key, result, ttl=3600)  # 1小时缓存
    log.info(f'基本面因子返回: {code} PE={result["pe_ttm"]:.1f} PB={result["pb"]:.2f} ROE={result["roe"]:.1f}% 评分={result["fundamental_score"]}')
    return jsonify(result)


@app.route('/fundamental_batch', methods=['POST'])
def get_fundamental_batch():
    """
    批量获取基本面因子（POST body: {"codes": ["000001","600519",...]}）
    用于 IC 检验的横截面数据拉取
    """
    data = request.get_json(silent=True) or {}
    codes = data.get('codes', [])
    if not codes:
        return jsonify({'error': '缺少codes参数'}), 400
    if len(codes) > 200:
        return jsonify({'error': '一次最多200只'}), 400

    results = []
    for code in codes:
        try:
            # 复用单股接口逻辑（有缓存保护）
            with app.test_request_context(f'/fundamental?code={code}'):
                from flask import request as r
                r.args = {'code': code}
            # 直接调用函数（避免 HTTP 开销）
            cache_key = f'fundamental_{code}'
            cached = cache_get(cache_key)
            if cached:
                results.append(cached)
            else:
                # 小批量时串行调用（避免限流）
                import time as time_mod
                time_mod.sleep(0.2)
                ts_code = code_to_ts(code)
                today = datetime.now().strftime('%Y%m%d')
                row_data = {'code': code, 'ts_code': ts_code,
                            'pe_ttm': 0, 'pb': 0, 'ps_ttm': 0, 'dv_ratio': 0,
                            'total_mv': 0, 'circ_mv': 0,
                            'roe': 0, 'roa': 0, 'gross_margin': 0,
                            'revenue_yoy': 0, 'profit_yoy': 0, 'debt_ratio': 0,
                            'fundamental_score': 0}
                try:
                    db = pro.daily_basic(ts_code=ts_code, trade_date=today,
                                          fields='ts_code,pe_ttm,pb,ps_ttm,dv_ratio,total_mv,circ_mv')
                    if db is not None and not db.empty:
                        r = db.iloc[0]
                        row_data.update({
                            'pe_ttm': safe_float(r.get('pe_ttm', 0)),
                            'pb':     safe_float(r.get('pb', 0)),
                            'ps_ttm': safe_float(r.get('ps_ttm', 0)),
                            'dv_ratio': safe_float(r.get('dv_ratio', 0)),
                            'total_mv': safe_float(r.get('total_mv', 0)),
                            'circ_mv':  safe_float(r.get('circ_mv', 0)),
                        })
                except Exception:
                    pass
                cache_set(cache_key, row_data, ttl=3600)
                results.append(row_data)
        except Exception as e:
            log.warning(f'批量基本面获取失败({code}): {e}')

    log.info(f'批量基本面返回: {len(results)} 只')
    return jsonify(results)


@app.route('/auction_realtime', methods=['GET'])
def get_auction_realtime():
    """
    【集合竞价专用】获取全市场实时竞价行情快照
    数据来源：新浪财经 hq.sinajs.cn 实时接口（在 9:15~9:25 竞价阶段有真实竞价价格）
    Tushare daily() 在竞价阶段只有昨日数据，不适合竞价选股，因此本接口绕过 Tushare。
    返回格式与 /market_daily 兼容：
      [{code, name, price, open, high, low, preClose, changePercent, change, volume, amount}, ...]
    """
    import urllib.request
    import re

    # 短期缓存：竞价阶段每30秒刷新一次（避免重复抓取导致IP限流）
    cache_key = 'auction_realtime'
    cached = cache_get(cache_key)
    if cached:
        log.info(f'竞价行情命中缓存: {len(cached)}只')
        return jsonify(cached)

    # Step1：获取全量A股代码列表
    # 优先级：1) 内存缓存 → 2) 本地文件（避免Tushare每小时限1次的频率限制）→ 3) Tushare接口
    STOCK_LIST_FILE = os.path.join(os.path.dirname(__file__), '../data/stock_basic_cache.json')
    basic_cache_key = 'stock_basic_map'
    basic_map = cache_get(basic_cache_key)

    if basic_map is None:
        # 尝试从本地文件加载（文件存在且不超过24小时）
        try:
            if os.path.exists(STOCK_LIST_FILE):
                file_age = time.time() - os.path.getmtime(STOCK_LIST_FILE)
                if file_age < CACHE_TTL_LONG:  # 24小时内有效
                    with open(STOCK_LIST_FILE, 'r', encoding='utf-8') as f:
                        basic_map = json.load(f)
                    cache_set(basic_cache_key, basic_map, ttl=CACHE_TTL_LONG)
                    log.info(f'股票列表从本地文件加载: {len(basic_map)} 只（文件年龄 {file_age/3600:.1f}h）')
        except Exception as e:
            log.warning(f'从本地文件加载股票列表失败: {e}')
            basic_map = None

    if basic_map is None:
        # 从 Tushare 获取（有频率限制，每小时1次）
        try:
            df_basic = pro.stock_basic(exchange='', list_status='L',
                                       fields='ts_code,symbol,name,industry,market')
            basic_map = {}
            if df_basic is not None and not df_basic.empty:
                for _, row in df_basic.iterrows():
                    basic_map[row['ts_code']] = {
                        'name':     row['name'],
                        'industry': row.get('industry', ''),
                        'market':   row.get('market', ''),
                    }
            cache_set(basic_cache_key, basic_map, ttl=CACHE_TTL_LONG)
            # 持久化到本地文件，避免频繁请求Tushare
            try:
                os.makedirs(os.path.dirname(STOCK_LIST_FILE), exist_ok=True)
                with open(STOCK_LIST_FILE, 'w', encoding='utf-8') as f:
                    json.dump(basic_map, f, ensure_ascii=False)
                log.info(f'股票列表已缓存到本地文件: {len(basic_map)} 只 → {STOCK_LIST_FILE}')
            except Exception as fe:
                log.warning(f'股票列表写入本地文件失败: {fe}')
        except Exception as e:
            log.warning(f'stock_basic 获取失败，使用空名称降级: {e}')
            basic_map = {}

    if not basic_map:
        log.warning('auction_realtime: 股票列表为空，无法请求新浪接口')
        return jsonify([])

    # 构建新浪格式代码列表：600519.SH → sh600519，000001.SZ → sz000001
    sina_codes = []
    ts_to_short = {}
    for ts_code in basic_map.keys():
        parts = ts_code.split('.')
        if len(parts) != 2:
            continue
        symbol = parts[0]
        market = parts[1].lower()  # SH → sh, SZ → sz
        # 北交所 BJ 新浪不支持，跳过
        if market == 'bj':
            continue
        sina_code = market + symbol
        sina_codes.append(sina_code)
        ts_to_short[sina_code] = symbol  # sh600519 → 600519

    log.info(f'auction_realtime: 准备请求新浪实时行情，共 {len(sina_codes)} 只...')

    # Step2：分批请求新浪 hq.sinajs.cn（每批最多800只）
    BATCH_SIZE = 800
    result = []
    headers = {
        'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) '
                      'AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
        'Referer': 'https://finance.sina.com.cn/',
    }

    for i in range(0, len(sina_codes), BATCH_SIZE):
        batch = sina_codes[i:i + BATCH_SIZE]
        url = 'https://hq.sinajs.cn/list=' + ','.join(batch)
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=15) as resp:
                # 新浪返回 GB18030 编码
                raw_bytes = resp.read()
                try:
                    body = raw_bytes.decode('gb18030')
                except Exception:
                    body = raw_bytes.decode('utf-8', errors='replace')

            # 每行格式：var hq_str_sh600519="贵州茅台,开盘,昨收,现价,最高,最低,...,日期,时间";
            for line in body.split('\n'):
                line = line.strip()
                if not line or '=""' in line:
                    continue
                # 提取 sina_code
                m = re.match(r'var hq_str_(\w+)="(.+)"', line)
                if not m:
                    continue
                sina_code = m.group(1)
                data_str  = m.group(2)
                parts_d   = data_str.split(',')
                if len(parts_d) < 32:
                    continue

                short_code = ts_to_short.get(sina_code, '')
                if not short_code:
                    continue

                # 新浪字段含义（A股，普通交易时段）:
                # 0=名称,1=今开,2=昨收,3=现价,4=今高,5=今低,
                # 6=买一价,7=买一量,8=卖一价,9=卖一量,
                # 10~19=买二~买五价/量, 20~29=卖二~卖五价/量
                # 30=成交量(手),31=成交额, 32=日期, 33=时间
                # !! 集合竞价阶段(9:15~9:25):
                #    parts[3]=现价=0, 但竞价价格在 parts[6]=买一价（当前撮合价）
                try:
                    name      = parts_d[0]
                    open_p    = safe_float(parts_d[1])
                    pre_close = safe_float(parts_d[2])
                    price     = safe_float(parts_d[3])
                    high      = safe_float(parts_d[4])
                    low       = safe_float(parts_d[5])
                    buy1_price = safe_float(parts_d[6])   # 买一价（竞价阶段的撮合价）
                    volume    = safe_float(parts_d[30]) if len(parts_d) > 30 else safe_float(parts_d[8])  # 手
                    amount    = safe_float(parts_d[31]) if len(parts_d) > 31 else safe_float(parts_d[9])  # 元

                    if pre_close <= 0:
                        continue
                    # 集合竞价阶段：现价为0，改用买一价（竞价撮合价）
                    if price <= 0 and buy1_price > 0:
                        price = buy1_price
                    # 竞价尚未开始（价格仍为0），跳过
                    if price <= 0:
                        continue
                    # 过滤ST、退市
                    if 'ST' in name or '退' in name:
                        continue

                    change_pct = round((price - pre_close) / pre_close * 100, 4)

                    # 昨日成交量：新浪实时接口不直接提供，但 parts[36] 在部分版本包含
                    # 保守起见置0，Java 端会用 volumeRatio 字段判断
                    result.append({
                        'code':          short_code,
                        'name':          name,
                        'price':         price,
                        'open':          open_p,
                        'high':          high,
                        'low':           low,
                        'preClose':      pre_close,
                        'changePercent': change_pct,
                        'change':        round(price - pre_close, 4),
                        'volume':        volume * 100,  # 手 → 股
                        'amount':        amount,
                        'isRealtime':    True,
                    })
                except Exception:
                    continue

            log.info(f'auction_realtime: 批次 {i//BATCH_SIZE + 1} 完成，累计 {len(result)} 只有效')
        except Exception as e:
            log.error(f'auction_realtime: 新浪批次 {i//BATCH_SIZE + 1} 请求失败: {e}')

    # 按涨幅降序
    result.sort(key=lambda x: x['changePercent'], reverse=True)

    # 竞价数据缓存30秒（让同一竞价窗口内的多次扫描复用缓存）
    cache_set(cache_key, result, ttl=30)
    log.info(f'auction_realtime: 返回 {len(result)} 只实时竞价行情')
    return jsonify(result)


@app.route('/suspend', methods=['GET'])
def get_suspend():
    """
    【停牌股票列表】获取当日停牌/复牌股票
    数据来源：Tushare pro.suspend_d (120积分)
    返回格式：[{"code": "600519", "name": "贵州茅台", "suspend_date": "20260310", "resume_date": "", "reason": "重要事项"}, ...]
    """
    # 缓存键：按日期缓存，每日仅请求一次
    today = datetime.now().strftime('%Y%m%d')
    cache_key = f'suspend_{today}'
    cached = cache_get(cache_key)
    if cached:
        log.info(f'停牌数据命中缓存: {len(cached)} 只')
        return jsonify(cached)

    try:
        # 获取当日停牌股票（suspend_type=S 表示停牌）
        df = pro.suspend_d(trade_date=today, suspend_type='S')
        if df is None or df.empty:
            cache_set(cache_key, [], ttl=CACHE_TTL_LONG)
            log.info(f'当日无停牌股票: {today}')
            return jsonify([])

        # 补全股票名称（从缓存的股票列表获取）
        stock_list_cache = cache_get('stock_basic_map')
        if stock_list_cache is None:
            # 尝试从本地文件加载
            STOCK_LIST_FILE = os.path.join(os.path.dirname(__file__), '../data/stock_basic_cache.json')
            if os.path.exists(STOCK_LIST_FILE):
                try:
                    with open(STOCK_LIST_FILE, 'r', encoding='utf-8') as f:
                        stock_list_cache = json.load(f)
                except Exception:
                    stock_list_cache = None

        result = []
        for _, row in df.iterrows():
            ts_code = row.get('ts_code', '')
            code = ts_code.replace('.SH', '').replace('.SZ', '').replace('.BJ', '') if ts_code else ''
            name = ''
            if stock_list_cache and ts_code in stock_list_cache:
                name = stock_list_cache[ts_code].get('name', '')
            result.append({
                'code': code,
                'ts_code': ts_code,
                'name': name,
                'suspend_date': row.get('suspend_date', ''),
                'resume_date': row.get('resume_date', ''),
                'reason': row.get('reason', ''),
            })

        # 按停牌日期降序（最新的在前）
        result.sort(key=lambda x: x['suspend_date'], reverse=True)

        # 缓存到当日收盘（24小时）
        cache_set(cache_key, result, ttl=CACHE_TTL_LONG)
        log.info(f'停牌数据返回: {len(result)} 只')
        return jsonify(result)

    except Exception as e:
        log.error(f'停牌数据获取失败: {e}')
        # 降级返回空列表（不停牌），避免阻塞选股
        return jsonify([])


@app.route('/zt_pool', methods=['GET'])
def get_zt_pool():
    """
    【涨停板池】获取当日涨停板股票列表（首板/连板/炸板）
    数据来源：AKShare stock_zt_pool_em (免费)
    返回格式：[{"code": "600519", "name": "贵州茅台", "price": 1850.0, "change_pct": 10.0, "limit_times": "首板", "first_time": "09:30:00", "break_reason": ""}, ...]
    """
    today = datetime.now().strftime('%Y%m%d')
    cache_key = f'zt_pool_{today}'
    cached = cache_get(cache_key)
    if cached:
        log.info(f'涨停板池命中缓存: {len(cached)} 只')
        return jsonify(cached)

    try:
        # 尝试导入 akshare（可选依赖，未安装时不报错）
        import akshare as ak
        df = ak.stock_zt_pool_em(date=today)
        if df is None or df.empty:
            cache_set(cache_key, [], ttl=CACHE_TTL_SHORT)
            return jsonify([])

        # 映射字段（AKShare 返回的列名可能有变化，做兼容处理）
        result = []
        for _, row in df.iterrows():
            # 尝试多种可能的列名
            code = str(row.get('代码', row.get('code', row.get('symbol', ''))))
            name = str(row.get('名称', row.get('name', row.get('股票名称', ''))))
            # 价格和涨跌幅
            try:
                price = float(row.get('最新价', row.get('price', row.get('现价', 0))))
            except:
                price = 0.0
            try:
                change_pct = float(row.get('涨跌幅', row.get('change_pct', row.get('涨幅', 0))))
            except:
                change_pct = 0.0
            # 板次：首板/2连板/3连板...
            limit_times = str(row.get('连板数', row.get('limit_times', row.get('第几板', '首板'))))
            first_time = str(row.get('首次封板时间', row.get('first_time', '')))
            break_reason = str(row.get('炸板原因', row.get('break_reason', '')))

            if code and price > 0:
                result.append({
                    'code': code,
                    'name': name,
                    'price': price,
                    'change_pct': change_pct,
                    'limit_times': limit_times,
                    'first_time': first_time,
                    'break_reason': break_reason,
                })

        cache_set(cache_key, result, ttl=CACHE_TTL_SHORT)
        log.info(f'涨停板池返回: {len(result)} 只')
        return jsonify(result)

    except ImportError:
        log.warning('AKShare 未安装，无法获取涨停板池')
        return jsonify({'error': 'AKShare 未安装'})
    except Exception as e:
        log.error(f'涨停板池获取失败: {e}')
        return jsonify([])


@app.route('/news', methods=['GET'])
def get_news():
    """
    【财经新闻】获取市场财经新闻列表
    数据来源：Tushare pro.news (120积分) 或 AKShare 备用
    参数: source=eastmoney/tushare (默认 eastmoney)
    返回格式：[{"title": "标题", "content": "内容摘要", "pub_date": "2026-03-10 10:00:00", "source": "新浪财经"}, ...]
    """
    source = request.args.get('source', 'eastmoney')
    today = datetime.now().strftime('%Y%m%d')
    cache_key = f'news_{source}_{today}'
    cached = cache_get(cache_key)
    if cached:
        log.info(f'新闻数据命中缓存: {len(cached)} 条')
        return jsonify(cached)

    result = []

    # 方案1: Tushare Pro news 接口 (120积分)
    if source == 'tushare':
        try:
            # Tushare news 接口返回的是新闻标题列表
            df = pro.news(start_date=today, fields='title,content,pub_date,source')
            if df is not None and not df.empty:
                for _, row in df.iterrows():
                    result.append({
                        'title': str(row.get('title', '')),
                        'content': str(row.get('content', ''))[:200] if row.get('content') else '',
                        'pub_date': str(row.get('pub_date', '')),
                        'source': str(row.get('source', 'Tushare')),
                    })
                cache_set(cache_key, result, ttl=CACHE_TTL_SHORT)
                log.info(f'新闻(Tushare)返回: {len(result)} 条')
                return jsonify(result)
        except Exception as e:
            log.warning(f'Tushare新闻获取失败: {e}')

    # 方案2: AKShare 东方财富财经新闻 (免费)
    try:
        import akshare as ak
        # 获取最新财经新闻
        news_df = ak.stock_info_global_em()
        if news_df is not None and not news_df.empty:
            for _, row in news_df.head(50).iterrows():  # 限制50条
                title = str(row.get('标题', row.get('title', '')))
                if title and title != 'nan':
                    result.append({
                        'title': title,
                        'content': '',
                        'pub_date': str(row.get('时间', row.get('time', ''))),
                        'source': '东方财富',
                    })
            cache_set(cache_key, result, ttl=CACHE_TTL_SHORT)
            log.info(f'新闻(AKShare)返回: {len(result)} 条')
            return jsonify(result)
    except ImportError:
        log.warning('AKShare 未安装，无法获取财经新闻')
    except Exception as e:
        log.warning(f'AKShare新闻获取失败: {e}')

    # 降级返回空列表
    return jsonify([])


@app.route('/stock_news', methods=['GET'])
def get_stock_news():
    """
    【个股新闻】获取特定股票的近期新闻
    参数: code=600519
    返回格式：[{"title": "标题", "pub_date": "2026-03-10 10:00:00", "sentiment": "positive/negative/neutral"}, ...]
    """
    code = request.args.get('code', '')
    if not code:
        return jsonify({'error': '缺少code参数'}), 400

    # 尝试多种数据源
    result = []

    # 方案1: Tushare 个股新闻 (120积分)
    try:
        ts_code = code_to_ts(code)
        df = pro.news(ts_code=ts_code)
        if df is not None and not df.empty:
            for _, row in df.iterrows():
                title = str(row.get('title', ''))
                pub_date = str(row.get('pub_date', ''))
                # 简单情感分析
                sentiment = analyze_sentiment(title)
                result.append({
                    'title': title,
                    'pub_date': pub_date,
                    'sentiment': sentiment,
                })
            cache_set(f'stock_news_{code}', result, ttl=CACHE_TTL_SHORT)
            log.info(f'个股新闻(Tushare) {code}: {len(result)} 条')
            return jsonify(result)
    except Exception as e:
        log.warning(f'Tushare个股新闻获取失败({code}): {e}')

    # 方案2: AKShare 个股新闻 (免费)
    try:
        import akshare as ak
        news_df = ak.stock_news_em(symbol=code)
        if news_df is not None and not news_df.empty:
            for _, row in news_df.head(20).iterrows():  # 限制20条
                title = str(row.get('新闻标题', row.get('title', '')))
                pub_date = str(row.get('发布时间', row.get('pub_date', '')))
                if title and title != 'nan':
                    sentiment = analyze_sentiment(title)
                    result.append({
                        'title': title,
                        'pub_date': pub_date,
                        'sentiment': sentiment,
                    })
            cache_set(f'stock_news_{code}', result, ttl=CACHE_TTL_SHORT)
            log.info(f'个股新闻(AKShare) {code}: {len(result)} 条')
            return jsonify(result)
    except ImportError:
        log.warning('AKShare 未安装')
    except Exception as e:
        log.warning(f'AKShare个股新闻获取失败({code}): {e}')

    return jsonify([])


def analyze_sentiment(title):
    """
    简单的情感分析（基于关键词）
    返回: positive(正面) / negative(负面) / neutral(中性)
    """
    if not title:
        return 'neutral'
    title_lower = title.lower()
    # 正面关键词
    positive_words = ['涨', '利好', '增持', '涨停', '突破', '创新高', '业绩增长', '盈利', '分红', '回购', '推荐', '买入', '看涨', '强劲', '上涨']
    # 负面关键词
    negative_words = ['跌', '利空', '减持', '跌停', '破位', '创新低', '亏损', '违规', '调查', '处罚', '警告', '卖出', '看跌', '下跌', '风险']

    pos_count = sum(1 for w in positive_words if w in title_lower)
    neg_count = sum(1 for w in negative_words if w in title_lower)

    if pos_count > neg_count:
        return 'positive'
    elif neg_count > pos_count:
        return 'negative'
    else:
        return 'neutral'


@app.route('/capital_flow', methods=['GET'])
def get_capital_flow():
    """
    【主力资金流向】获取股票主力资金净流入数据
    数据来源：AKShare stock_individual_fund_flow (免费)
    参数: code=600519
    返回格式：[{"code": "600519", "name": "贵州茅台", "net_main_inflow": 1.5, "main_inflow_rate": 2.5}, ...]
    net_main_inflow: 主力净流入（亿元）
    main_inflow_rate: 主力净流入占比（%）
    """
    code = request.args.get('code', '')
    if not code:
        return jsonify({'error': '缺少code参数'}), 400

    cache_key = f'capital_flow_{code}'
    cached = cache_get(cache_key)
    if cached:
        return jsonify(cached)

    result = {}

    # 使用 AKShare 获取主力资金流向
    try:
        import akshare as ak
        # 个股资金流向（最近5日）
        df = ak.stock_individual_fund_flow(stock=code)
        if df is not None and not df.empty:
            # 取最新一天的数据
            latest = df.iloc[0]
            # 尝试获取主力净流入（字段名可能有变化）
            net_main = 0.0
            main_rate = 0.0
            for col in ['主力净流入-净额', '主力净流入', 'net_main_inflow']:
                if col in latest.index:
                    try:
                        net_main = float(latest[col])
                        break
                    except:
                        pass
            for col in ['主力净流入-净占比', '主力净流入占比', 'main_inflow_rate']:
                if col in latest.index:
                    try:
                        main_rate = float(latest[col])
                        break
                    except:
                        pass
            result = {
                'code': code,
                'net_main_inflow': net_main,
                'main_inflow_rate': main_rate,
            }
            cache_set(cache_key, result, ttl=CACHE_TTL_SHORT)
            log.info(f'主力资金流向 {code}: 净流入={net_main}亿 占比={main_rate}%')
            return jsonify(result)
    except ImportError:
        log.warning('AKShare 未安装，无法获取主力资金流向')
    except Exception as e:
        log.warning(f'主力资金流向获取失败({code}): {e}')

    return jsonify({'code': code, 'net_main_inflow': 0, 'main_inflow_rate': 0})


@app.route('/capital_flow_batch', methods=['GET'])
def get_capital_flow_batch():
    """
    【批量主力资金流向】获取多只股票主力资金数据
    参数: codes=600519,000001,300750
    返回格式：[{"code": "600519", "net_main_inflow": 1.5}, ...]
    """
    codes_str = request.args.get('codes', '')
    if not codes_str:
        return jsonify({'error': '缺少codes参数'}), 400

    codes = codes_str.split(',')
    result = []

    # 限制批量数量，避免超时
    codes = codes[:20]

    try:
        import akshare as ak
        for code in codes:
            try:
                df = ak.stock_individual_fund_flow(stock=code)
                if df is not None and not df.empty:
                    latest = df.iloc[0]
                    net_main = 0.0
                    for col in ['主力净流入-净额', '主力净流入', 'net_main_inflow']:
                        if col in latest.index:
                            try:
                                net_main = float(latest[col])
                                break
                            except:
                                pass
                    result.append({
                        'code': code,
                        'net_main_inflow': net_main,
                    })
            except Exception:
                result.append({'code': code, 'net_main_inflow': 0})
    except ImportError:
        log.warning('AKShare 未安装')
    except Exception as e:
        log.warning(f'批量资金流向获取失败: {e}')

    return jsonify(result)


# ========== 启动 ==========
if __name__ == '__main__':
    log.info('=' * 50)
    log.info('  股票数据服务启动中...')
    log.info(f'  端口: {SERVICE_PORT}')
    log.info(f'  Tushare Token: {TUSHARE_TOKEN[:8]}...')
    log.info('  接口列表:')
    log.info(f'    GET /health            - 健康检查')
    log.info(f'    GET /kline             - 日K线数据')
    log.info(f'    GET /realtime          - 实时行情')
    log.info(f'    GET /stock_list        - 全量股票列表')
    log.info(f'    GET /batch_realtime    - 批量实时行情')
    log.info(f'    GET /market_daily      - 全市场日行情')
    log.info(f'    GET /auction_realtime  - 集合竞价实时行情（新浪实时源，绕过Tushare）')
log.info(f'    GET /fundamental       - 单股基本面因子')
log.info(f'    POST /fundamental_batch - 批量基本面因子')
log.info(f'    GET /suspend           - 当日停牌股票列表（Tushare 120积分）')
log.info(f'    GET /zt_pool          - 当日涨停板池（AKShare 免费）')
log.info(f'    GET /news             - 市场财经新闻列表')
log.info(f'    GET /stock_news       - 个股新闻（含情感分析）')
log.info(f'    GET /capital_flow     - 主力资金流向（AKShare 免费）')
log.info('=' * 50)
app.run(host='0.0.0.0', port=SERVICE_PORT, debug=False)


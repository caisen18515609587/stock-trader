#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QMT/MiniQMT 实盘桥接服务
为 Java 交易引擎提供实盘下单、持仓同步、账户查询等 HTTP 接口。

依赖：
  pip install xtquant  # 迅投 MiniQMT Python SDK（需安装 MiniQMT 客户端后才可用）

启动：
  python3 qmt_bridge.py

默认端口：8098
API 文档：
  POST /order/buy          买入下单
  POST /order/sell         卖出下单
  POST /order/cancel       撤单
  GET  /order/status/{id}  查询单笔委托状态
  GET  /orders/today       查询今日所有委托
  GET  /positions          查询实盘持仓
  GET  /account            查询账户资金
  GET  /health             健康检查

注意：
  1. 必须在已登录 MiniQMT 客户端的机器上运行本服务。
  2. 账户 ID 通过环境变量 QMT_ACCOUNT_ID 配置，或修改 DEFAULT_ACCOUNT_ID 常量。
  3. 本服务仅负责中转，不做任何风险判断，所有风控逻辑由 Java 端 AutoTrader 实现。
"""

import logging
import os
import sys
import threading
import time
from datetime import datetime
from flask import Flask, jsonify, request

# ===================== 配置 =====================
SERVICE_PORT     = 8098
DEFAULT_ACCOUNT_ID = os.environ.get("QMT_ACCOUNT_ID", "")   # 必须配置
LOG_LEVEL        = logging.INFO
ORDER_QUERY_TIMEOUT = 30   # 下单后等待初始回调的最长秒数（用于同步接口）

# ===================== 日志 =====================
logging.basicConfig(
    level=LOG_LEVEL,
    format='%(asctime)s [%(levelname)s] %(name)s - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S',
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler('qmt_bridge.log', encoding='utf-8')
    ]
)
log = logging.getLogger('qmt_bridge')

app = Flask(__name__)

# ===================== MiniQMT SDK 初始化 =====================
QMT_AVAILABLE = False
xt_trader = None
xt_data   = None

try:
    from xtquant import xtdata
    from xtquant.xttrader import XtQuantTrader, XtQuantTraderCallback
    from xtquant.xttype import StockOrderType, StockOrderSide

    # MiniQMT 默认路径（Windows 迅投客户端安装路径）
    _MINITRADER_PATH = os.environ.get(
        "MINITRADER_PATH",
        r"C:\国金证券QMT交易端\userdata_mini"   # 根据实际安装路径修改
    )

    class _TraderCallback(XtQuantTraderCallback):
        """MiniQMT 异步回调：将委托回报写入本地缓存，供 /order/status 接口查询"""

        def on_disconnected(self):
            log.warning("[QMT] 与交易服务器断开连接")

        def on_stock_order(self, order):
            """委托状态回调"""
            _order_cache[str(order.order_id)] = _order_to_dict(order)
            log.info("[QMT回调] 委托更新 orderId=%s status=%s %s %d@%.2f",
                     order.order_id, order.order_status,
                     order.stock_code, order.order_volume, order.price)

        def on_stock_trade(self, trade):
            """成交回报回调"""
            oid = str(trade.order_id)
            cached = _order_cache.get(oid, {})
            cached['filled_volume']     = trade.traded_volume
            cached['filled_price']      = trade.traded_price
            cached['filled_amount']     = trade.traded_amount
            cached['trade_time']        = trade.traded_time
            cached['status']            = 'FILLED' if trade.traded_volume >= cached.get('volume', 0) else 'PARTIAL_FILLED'
            _order_cache[oid] = cached
            log.info("[QMT回调] 成交 orderId=%s %s %d@%.2f",
                     trade.order_id, trade.stock_code,
                     trade.traded_volume, trade.traded_price)

        def on_order_error(self, order_error):
            oid = str(order_error.order_id)
            cached = _order_cache.get(oid, {})
            cached['status'] = 'REJECTED'
            cached['error_msg'] = order_error.error_msg
            _order_cache[oid] = cached
            log.error("[QMT回调] 委托错误 orderId=%s msg=%s", order_error.order_id, order_error.error_msg)

        def on_cancel_error(self, cancel_error):
            log.error("[QMT回调] 撤单错误 orderId=%s msg=%s", cancel_error.order_id, cancel_error.error_msg)

    # 连接 MiniQMT
    session_id = int(time.time()) % 100000
    xt_trader = XtQuantTrader(_MINITRADER_PATH, session_id)
    callback   = _TraderCallback()
    xt_trader.register_callback(callback)
    xt_trader.start()

    connect_result = xt_trader.connect()
    if connect_result == 0:
        QMT_AVAILABLE = True
        log.info("[QMT] 连接成功，会话ID: %d", session_id)
        # 订阅账户回调
        if DEFAULT_ACCOUNT_ID:
            from xtquant.xttype import StockAccount
            _account = StockAccount(DEFAULT_ACCOUNT_ID)
            xt_trader.subscribe(account=_account)
            log.info("[QMT] 已订阅账户: %s", DEFAULT_ACCOUNT_ID)
    else:
        log.error("[QMT] 连接失败，返回码: %d。服务将以模拟模式运行（所有操作返回 mock 数据）", connect_result)

    xt_data = xtdata

except ImportError:
    log.warning("[QMT] xtquant SDK 未安装，服务将以【模拟模式】运行。")
    log.warning("      安装方式: pip install xtquant  (需先安装迅投 MiniQMT 客户端)")
except Exception as e:
    log.error("[QMT] 初始化失败: %s，服务将以【模拟模式】运行。", str(e))

# ===================== 内存缓存 =====================
_order_cache = {}         # orderId -> order_dict
_order_lock  = threading.Lock()

# ===================== 辅助函数 =====================

def _get_account():
    """获取账户对象（每次调用都从环境变量重读，支持热更新）"""
    account_id = os.environ.get("QMT_ACCOUNT_ID", DEFAULT_ACCOUNT_ID)
    if not account_id:
        raise ValueError("QMT_ACCOUNT_ID 未配置，请设置环境变量或修改 DEFAULT_ACCOUNT_ID 常量")
    if QMT_AVAILABLE:
        from xtquant.xttype import StockAccount
        return StockAccount(account_id)
    return None


def _order_to_dict(order) -> dict:
    """将 QMT Order 对象转为可序列化 dict"""
    status_map = {
        48: 'PENDING',      # '0' 未报
        49: 'SUBMITTED',    # '1' 待报
        50: 'SUBMITTED',    # '2' 已报
        51: 'PARTIAL_FILLED',  # '3' 已报待撤
        52: 'CANCELLED',    # '4' 部撤
        53: 'CANCELLED',    # '5' 已撤
        54: 'PARTIAL_FILLED',  # '6' 部成
        55: 'FILLED',       # '7' 已成
        56: 'REJECTED',     # '8' 废单
        57: 'PENDING',      # '9' 待审
    }
    return {
        'order_id'      : str(order.order_id),
        'stock_code'    : order.stock_code,
        'order_type'    : 'BUY' if order.order_side == 23 else 'SELL',  # 23=买, 24=卖
        'price'         : order.price,
        'volume'        : order.order_volume,
        'filled_volume' : order.traded_volume if hasattr(order, 'traded_volume') else 0,
        'filled_price'  : order.traded_price  if hasattr(order, 'traded_price')  else 0.0,
        'status'        : status_map.get(ord(order.order_status) if isinstance(order.order_status, str) else order.order_status, 'UNKNOWN'),
        'order_time'    : str(order.order_time) if hasattr(order, 'order_time') else '',
        'remark'        : order.remark if hasattr(order, 'remark') else '',
    }


def _mock_order_id() -> str:
    return "MOCK_" + str(int(time.time() * 1000))


def _build_qmt_code(stock_code: str) -> str:
    """
    将 6 位 A 股代码转换为 QMT 格式（带交易所后缀）
    600519 -> 600519.SH
    000001 -> 000001.SZ
    300750 -> 300750.SZ
    """
    code = stock_code.strip()
    if '.' in code:
        return code  # 已有后缀，直接返回
    if code.startswith('6') or code.startswith('5') or code.startswith('11'):
        return code + '.SH'
    elif code.startswith('8') or code.startswith('4'):
        return code + '.BJ'
    else:
        return code + '.SZ'


# ===================== HTTP 接口 =====================

@app.route('/health', methods=['GET'])
def health():
    """健康检查"""
    account_id = os.environ.get("QMT_ACCOUNT_ID", DEFAULT_ACCOUNT_ID)
    return jsonify({
        'status'      : 'ok',
        'qmt_available': QMT_AVAILABLE,
        'mock_mode'   : not QMT_AVAILABLE,
        'account_id'  : account_id if account_id else '未配置',
        'time'        : datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    })


@app.route('/order/buy', methods=['POST'])
def place_buy_order():
    """
    买入下单
    请求体 JSON：
    {
        "stock_code": "600519",   // 6 位股票代码
        "price": 1800.0,          // 委托价格（0 = 市价）
        "volume": 100,            // 委托数量（股）
        "order_id_ref": "xxx",    // Java 端自定义引用 ID（用于关联回报，可选）
        "remark": "策略名称"       // 备注（可选）
    }
    返回：
    {
        "success": true,
        "order_id": "123456",   // QMT 返回的委托编号
        "mock": false           // 是否为模拟模式
    }
    """
    data = request.get_json(force=True)
    stock_code = data.get('stock_code', '').strip()
    price      = float(data.get('price', 0))
    volume     = int(data.get('volume', 0))
    remark     = data.get('remark', '')

    if not stock_code or volume <= 0:
        return jsonify({'success': False, 'error': '参数错误：stock_code 和 volume 不能为空'}), 400

    qmt_code = _build_qmt_code(stock_code)
    log.info("[下单] BUY %s 价格=%.2f 数量=%d 备注=%s", qmt_code, price, volume, remark)

    if not QMT_AVAILABLE:
        # 模拟模式：生成 mock orderId，立即返回「已提交」
        mock_id = _mock_order_id()
        with _order_lock:
            _order_cache[mock_id] = {
                'order_id'     : mock_id,
                'stock_code'   : stock_code,
                'order_type'   : 'BUY',
                'price'        : price,
                'volume'       : volume,
                'filled_volume': volume,
                'filled_price' : price,
                'status'       : 'FILLED',    # 模拟模式直接标记为成交
                'order_time'   : datetime.now().strftime('%Y%m%d%H%M%S'),
                'remark'       : remark,
                'mock'         : True
            }
        log.info("[下单][模拟] BUY %s orderId=%s", stock_code, mock_id)
        return jsonify({'success': True, 'order_id': mock_id, 'mock': True})

    try:
        account = _get_account()
        order_type = StockOrderType.STOCK_MARKET_SH_CONVERT_5_CANCEL if price == 0 \
                     else StockOrderType.STOCK_LIMIT_ORDER
        order_id = xt_trader.order_stock(
            account    = account,
            stock_code = qmt_code,
            order_type = order_type,
            order_side = StockOrderSide.STOCK_BUY,
            volume     = volume,
            price      = price,
            strategy_name = remark,
            order_remark  = remark
        )
        if order_id == -1:
            log.error("[下单] BUY %s 下单失败，QMT 返回 -1", qmt_code)
            return jsonify({'success': False, 'error': 'QMT 下单返回 -1，可能持仓不足或账户异常'}), 500

        with _order_lock:
            _order_cache[str(order_id)] = {
                'order_id'     : str(order_id),
                'stock_code'   : stock_code,
                'order_type'   : 'BUY',
                'price'        : price,
                'volume'       : volume,
                'filled_volume': 0,
                'filled_price' : 0.0,
                'status'       : 'SUBMITTED',
                'order_time'   : datetime.now().strftime('%Y%m%d%H%M%S'),
                'remark'       : remark,
                'mock'         : False
            }
        log.info("[下单] BUY %s 成功，orderId=%s", qmt_code, order_id)
        return jsonify({'success': True, 'order_id': str(order_id), 'mock': False})

    except Exception as e:
        log.exception("[下单] BUY %s 异常: %s", stock_code, str(e))
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/order/sell', methods=['POST'])
def place_sell_order():
    """
    卖出下单
    请求体 JSON：同 /order/buy
    """
    data = request.get_json(force=True)
    stock_code = data.get('stock_code', '').strip()
    price      = float(data.get('price', 0))
    volume     = int(data.get('volume', 0))
    remark     = data.get('remark', '')

    if not stock_code or volume <= 0:
        return jsonify({'success': False, 'error': '参数错误'}), 400

    qmt_code = _build_qmt_code(stock_code)
    log.info("[下单] SELL %s 价格=%.2f 数量=%d 备注=%s", qmt_code, price, volume, remark)

    if not QMT_AVAILABLE:
        mock_id = _mock_order_id()
        with _order_lock:
            _order_cache[mock_id] = {
                'order_id'     : mock_id,
                'stock_code'   : stock_code,
                'order_type'   : 'SELL',
                'price'        : price,
                'volume'       : volume,
                'filled_volume': volume,
                'filled_price' : price,
                'status'       : 'FILLED',
                'order_time'   : datetime.now().strftime('%Y%m%d%H%M%S'),
                'remark'       : remark,
                'mock'         : True
            }
        log.info("[下单][模拟] SELL %s orderId=%s", stock_code, mock_id)
        return jsonify({'success': True, 'order_id': mock_id, 'mock': True})

    try:
        account = _get_account()
        order_type = StockOrderType.STOCK_MARKET_SH_CONVERT_5_CANCEL if price == 0 \
                     else StockOrderType.STOCK_LIMIT_ORDER
        order_id = xt_trader.order_stock(
            account    = account,
            stock_code = qmt_code,
            order_type = order_type,
            order_side = StockOrderSide.STOCK_SELL,
            volume     = volume,
            price      = price,
            strategy_name = remark,
            order_remark  = remark
        )
        if order_id == -1:
            log.error("[下单] SELL %s 下单失败，QMT 返回 -1", qmt_code)
            return jsonify({'success': False, 'error': 'QMT 下单返回 -1'}), 500

        with _order_lock:
            _order_cache[str(order_id)] = {
                'order_id'     : str(order_id),
                'stock_code'   : stock_code,
                'order_type'   : 'SELL',
                'price'        : price,
                'volume'       : volume,
                'filled_volume': 0,
                'filled_price' : 0.0,
                'status'       : 'SUBMITTED',
                'order_time'   : datetime.now().strftime('%Y%m%d%H%M%S'),
                'remark'       : remark,
                'mock'         : False
            }
        log.info("[下单] SELL %s 成功，orderId=%s", qmt_code, order_id)
        return jsonify({'success': True, 'order_id': str(order_id), 'mock': False})

    except Exception as e:
        log.exception("[下单] SELL %s 异常: %s", stock_code, str(e))
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/order/cancel', methods=['POST'])
def cancel_order():
    """
    撤单
    请求体 JSON：{"order_id": "123456"}
    """
    data     = request.get_json(force=True)
    order_id = str(data.get('order_id', ''))
    if not order_id:
        return jsonify({'success': False, 'error': 'order_id 不能为空'}), 400

    log.info("[撤单] orderId=%s", order_id)

    if not QMT_AVAILABLE:
        with _order_lock:
            if order_id in _order_cache:
                _order_cache[order_id]['status'] = 'CANCELLED'
        return jsonify({'success': True, 'mock': True})

    try:
        account  = _get_account()
        result   = xt_trader.cancel_order_stock(account, int(order_id))
        success  = (result == 0)
        if success:
            with _order_lock:
                if order_id in _order_cache:
                    _order_cache[order_id]['status'] = 'CANCELLED'
        log.info("[撤单] orderId=%s 结果=%s", order_id, "成功" if success else "失败")
        return jsonify({'success': success, 'mock': False})
    except Exception as e:
        log.exception("[撤单] orderId=%s 异常: %s", order_id, str(e))
        return jsonify({'success': False, 'error': str(e)}), 500


@app.route('/order/status/<order_id>', methods=['GET'])
def get_order_status(order_id):
    """
    查询单笔委托状态
    返回：
    {
        "order_id": "123456",
        "stock_code": "600519",
        "order_type": "BUY",
        "price": 1800.0,
        "volume": 100,
        "filled_volume": 100,
        "filled_price": 1798.5,
        "status": "FILLED",   // PENDING/SUBMITTED/PARTIAL_FILLED/FILLED/CANCELLED/REJECTED
        "order_time": "20260309093500",
        "remark": ""
    }
    """
    # 先查本地缓存
    with _order_lock:
        cached = _order_cache.get(str(order_id))
    if cached:
        return jsonify(cached)

    # 缓存未命中，向 QMT 查询
    if not QMT_AVAILABLE:
        return jsonify({'error': 'order not found'}), 404

    try:
        account = _get_account()
        orders  = xt_trader.query_stock_orders(account, cancelable_only=False)
        for o in (orders or []):
            if str(o.order_id) == str(order_id):
                d = _order_to_dict(o)
                with _order_lock:
                    _order_cache[str(order_id)] = d
                return jsonify(d)
        return jsonify({'error': 'order not found'}), 404
    except Exception as e:
        log.exception("[查委托] orderId=%s 异常: %s", order_id, str(e))
        return jsonify({'error': str(e)}), 500


@app.route('/orders/today', methods=['GET'])
def get_today_orders():
    """查询今日所有委托"""
    if not QMT_AVAILABLE:
        with _order_lock:
            return jsonify(list(_order_cache.values()))

    try:
        account = _get_account()
        orders  = xt_trader.query_stock_orders(account, cancelable_only=False)
        result  = [_order_to_dict(o) for o in (orders or [])]
        # 同步更新缓存
        with _order_lock:
            for d in result:
                _order_cache[d['order_id']] = d
        return jsonify(result)
    except Exception as e:
        log.exception("[查今日委托] 异常: %s", str(e))
        return jsonify({'error': str(e)}), 500


@app.route('/positions', methods=['GET'])
def get_positions():
    """
    查询实盘持仓
    返回列表，每项：
    {
        "stock_code": "600519",
        "stock_name": "贵州茅台",
        "quantity": 100,
        "available_quantity": 100,
        "avg_cost": 1750.0,
        "current_price": 1800.0,
        "market_value": 180000.0,
        "profit": 5000.0,
        "profit_rate": 2.86
    }
    """
    if not QMT_AVAILABLE:
        return jsonify([])

    try:
        account   = _get_account()
        positions = xt_trader.query_stock_positions(account)
        result = []
        for p in (positions or []):
            code = p.stock_code.split('.')[0]   # 去掉交易所后缀，返回纯 6 位代码
            profit_rate = 0.0
            if p.avg_price and p.avg_price > 0 and p.market_value and p.volume > 0:
                profit_rate = (p.market_value - p.avg_price * p.volume) / (p.avg_price * p.volume) * 100
            result.append({
                'stock_code'        : code,
                'stock_name'        : p.stock_name if hasattr(p, 'stock_name') else code,
                'quantity'          : int(p.volume),
                'available_quantity': int(p.can_use_volume),
                'avg_cost'          : float(p.avg_price),
                'current_price'     : float(p.market_value / p.volume) if p.volume > 0 else 0.0,
                'market_value'      : float(p.market_value),
                'profit'            : float(p.market_value - p.avg_price * p.volume) if p.avg_price > 0 else 0.0,
                'profit_rate'       : round(profit_rate, 4),
            })
        return jsonify(result)
    except Exception as e:
        log.exception("[查持仓] 异常: %s", str(e))
        return jsonify({'error': str(e)}), 500


@app.route('/account', methods=['GET'])
def get_account_info():
    """
    查询账户资金
    返回：
    {
        "total_assets": 1000000.0,
        "available_cash": 200000.0,
        "frozen_cash": 0.0,
        "market_value": 800000.0,
        "profit": 50000.0,
        "profit_rate": 5.26
    }
    """
    if not QMT_AVAILABLE:
        return jsonify({
            'total_assets'  : 0,
            'available_cash': 0,
            'frozen_cash'   : 0,
            'market_value'  : 0,
            'profit'        : 0,
            'profit_rate'   : 0,
            'mock'          : True
        })

    try:
        account  = _get_account()
        asset    = xt_trader.query_stock_asset(account)
        if asset is None:
            return jsonify({'error': '无法获取账户资金，请检查账户 ID 是否正确'}), 500

        total        = float(asset.total_asset)
        cash         = float(asset.cash)
        frozen       = float(asset.frozen_cash) if hasattr(asset, 'frozen_cash') else 0.0
        market_value = float(asset.market_value) if hasattr(asset, 'market_value') else total - cash - frozen
        # 初始资金 = 总资产 - 盈亏（QMT 不直接提供初始资金，用 total - profit 近似）
        profit       = float(asset.profit) if hasattr(asset, 'profit') else 0.0
        initial      = total - profit
        profit_rate  = profit / initial * 100 if initial > 0 else 0.0

        return jsonify({
            'total_assets'  : total,
            'available_cash': cash,
            'frozen_cash'   : frozen,
            'market_value'  : market_value,
            'profit'        : profit,
            'profit_rate'   : round(profit_rate, 4),
            'mock'          : False
        })
    except Exception as e:
        log.exception("[查资金] 异常: %s", str(e))
        return jsonify({'error': str(e)}), 500


# ===================== 启动 =====================
if __name__ == '__main__':
    log.info("=" * 60)
    log.info("QMT 实盘桥接服务启动，端口: %d", SERVICE_PORT)
    log.info("QMT 可用: %s，模拟模式: %s", QMT_AVAILABLE, not QMT_AVAILABLE)
    log.info("账户 ID: %s", os.environ.get("QMT_ACCOUNT_ID", DEFAULT_ACCOUNT_ID) or "【未配置！请设置环境变量 QMT_ACCOUNT_ID】")
    log.info("=" * 60)
    app.run(host='0.0.0.0', port=SERVICE_PORT, debug=False, threaded=True)


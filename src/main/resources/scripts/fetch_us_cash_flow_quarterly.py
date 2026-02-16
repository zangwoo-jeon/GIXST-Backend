import pymysql
import requests
from bs4 import BeautifulSoup
from tqdm import tqdm
import time
import warnings

# Suppress warnings
warnings.filterwarnings("ignore")

# DB Configuration
DB_CONFIG = {
    'host': '100.77.73.46',
    'port': 3306,
    'user': 'user',
    'password': '0717',
    'db': 'aisa_portfolio',
    'charset': 'utf8',
    'cursorclass': pymysql.cursors.DictCursor
}

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
}

def get_target_stocks():
    """
    Fetch stocks that are US_STOCK and NOT suspended.
    """
    try:
        connection = pymysql.connect(**DB_CONFIG)
        with connection.cursor() as cursor:
            # is_suspended가 0(False)이거나 NULL인 경우만 조회
            sql = "SELECT stock_code FROM stock WHERE stock_type IN ('US_STOCK') AND (is_suspended IS NULL OR is_suspended = 0)"
            cursor.execute(sql)
            result = cursor.fetchall()
            return [row['stock_code'] for row in result]
    except Exception as e:
        print(f"❌ DB connection failed: {e}")
        return []
    finally:
        if 'connection' in locals():
            connection.close()

def parse_number(text: str):
    """
    숫자 파싱:
    - 콤마 제거
    - '-' 또는 공백은 None 처리
    """
    if not text:
        return 0.0

    text = text.replace(",", "").strip()
    if text in ("-", ""):
        return 0.0

    try:
        return float(text)
    except ValueError:
        return 0.0

def fetch_dividend_and_capital_change_quarterly(stock_code):
    """
    한경 글로벌마켓에서 해당 종목의 분기 현금흐름표를 크롤링
    """
    formatted_code = stock_code.lower().replace('.', '')
    url = f"https://www.hankyung.com/globalmarket/equities/americas/{formatted_code}"
    
    try:
        res = requests.get(url, headers=HEADERS, timeout=10)
        res.raise_for_status()
    except Exception as e:
        print(f"⚠️ Failed to fetch {stock_code}: {e}")
        return []

    soup = BeautifulSoup(res.text, "html.parser")

    # 1️⃣ 분기 재무 영역 (finance-statements-quarter)
    quarter_section = soup.select_one("div.finance-statements-quarter")
    if not quarter_section:
        return []

    # 2️⃣ 현금흐름표 item 찾기
    cashflow_item = None
    for item in quarter_section.select("div.item"):
        title = item.select_one("strong.module-stit")
        if title and "현금흐름표" in title.text.strip():
            cashflow_item = item
            break

    if not cashflow_item:
        return []

    # 3️⃣ 분기 헤더
    raw_periods_cols = cashflow_item.select("table.table-striped-col thead th strong")
    raw_periods = [th.text.strip() for th in raw_periods_cols]

    valid_indexes = []
    periods = []
    
    for i, p in enumerate(raw_periods):
        if "기간" in p or not p:
            continue
            
        # 날짜 파싱 (2024-09-30, 2024/09 등)
        clean_date = p.replace('/', '').replace('-', '').strip()
        
        # YYYYMM 형태로 변환
        formatted_yymm = ""
        if len(clean_date) >= 6:
            formatted_yymm = clean_date[:6]
        
        if formatted_yymm:
            valid_indexes.append(i)
            periods.append(formatted_yymm)

    # 4️⃣ 배당금 지급 (Cash Dividends Paid)
    div_row = cashflow_item.select_one("tr.div_cf")
    div_values = []
    
    if div_row:
        tds = div_row.select("td")
        for i in valid_indexes:
            if i < len(tds):
                div_values.append(parse_number(tds[i].text))
            else:
                div_values.append(0.0)
    else:
        div_values = [0.0] * len(valid_indexes)

    # 5️⃣ 자본금 변동 (Repurchase)
    cap_row = cashflow_item.select_one("tr.stk_chg_cf")
    cap_values = []
    
    if cap_row:
        tds = cap_row.select("td")
        for i in valid_indexes:
            if i < len(tds):
                cap_values.append(parse_number(tds[i].text))
            else:
                cap_values.append(0.0)
    else:
        cap_values = [0.0] * len(valid_indexes)

    # 6️⃣ 결과 정리
    result = []
    for i, yymm in enumerate(periods):
        repurchase_val = 0.0
        cap_change = cap_values[i]
        
        if cap_change < 0:
            repurchase_val = abs(cap_change)
        
        dividend_paid_val = abs(div_values[i])

        result.append({
            "stac_yymm": yymm,
            "div_code": "1", # Quarterly
            "repurchase_of_capital_stock": repurchase_val,
            "cash_dividends_paid": dividend_paid_val
        })

    return result

def save_cash_flows(stock_codes):
    """
    Hankyung -> DB Upsert (Quarterly)
    """
    try:
        connection = pymysql.connect(**DB_CONFIG)
        
        for ticker in tqdm(stock_codes, desc="Fetching Quarterly Cash Flows"):
            data_list = fetch_dividend_and_capital_change_quarterly(ticker)
            
            if not data_list:
                time.sleep(0.1)
                continue
                
            with connection.cursor() as cursor:
                for row in data_list:
                    # div_code='1' (분기)
                    sql = """
                    INSERT INTO overseas_stock_cash_flow 
                    (stock_code, stac_yymm, div_code, repurchase_of_capital_stock, cash_dividends_paid, shareholder_return_rate)
                    VALUES (%s, %s, %s, %s, %s, 0)
                    ON DUPLICATE KEY UPDATE
                    repurchase_of_capital_stock = VALUES(repurchase_of_capital_stock),
                    cash_dividends_paid = VALUES(cash_dividends_paid)
                    """
                    
                    cursor.execute(sql, (
                        ticker, 
                        row['stac_yymm'], 
                        row['div_code'], 
                        row['repurchase_of_capital_stock'], 
                        row['cash_dividends_paid']
                    ))
            
            connection.commit()
            time.sleep(0.5)
                
    except Exception as e:
        print(f"❌ DB connection error: {e}")
    finally:
        if 'connection' in locals():
            connection.close()

if __name__ == "__main__":
    print("🚀 Starting Quarterly Shareholder Return (Cash Flow) update for US Stocks...")
    stocks = get_target_stocks()
    
    if not stocks:
        print("❌ No active US stocks found.")
    else:
        print(f"✅ Found {len(stocks)} active US stocks.")
        save_cash_flows(stocks)
        print("\n✨ Quarterly Cash Flow update complete!")

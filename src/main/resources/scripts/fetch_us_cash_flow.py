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
            sql = "SELECT stock_code FROM stock WHERE stock_type = 'US_STOCK' AND (is_suspended IS NULL OR is_suspended = 0)"
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
    - 괄호 (123) -> 음수 -123 처리는 한경 사이트 특성에 맞게 확인 필요 (보통 -로 표시됨)
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

def fetch_dividend_and_capital_change(stock_code):
    """
    한경 글로벌마켓에서 해당 종목의 현금흐름표를 크롤링하여
    배당금 지급(cash_dividends_paid)과 자본금 변동(repurchase_of_capital_stock)을 추출
    """
    # stock_code format: AAPL, TSLA, BRK/B etc.
    # URL format: https://www.hankyung.com/globalmarket/equities/americas/aapl
    # BRK/B -> brkb
    formatted_code = stock_code.lower().replace('/', '')
    url = f"https://www.hankyung.com/globalmarket/equities/americas/{formatted_code}"
    
    try:
        res = requests.get(url, headers=HEADERS, timeout=10)
        res.raise_for_status() # 404 등 에러 발생 시 예외 처리
    except Exception as e:
        print(f"⚠️ Failed to fetch {stock_code}: {e}")
        return []

    soup = BeautifulSoup(res.text, "html.parser")

    # 1️⃣ 연간 재무 영역
    year_section = soup.select_one("div.finance-statements-year")
    if not year_section:
        # 데이터가 없는 경우 (ETF나 상장 폐지 등)
        return []

    # 2️⃣ 현금흐름표 item 찾기
    cashflow_item = None
    for item in year_section.select("div.item"):
        title = item.select_one("strong.module-stit")
        if title and "현금흐름표" in title.text.strip():
            cashflow_item = item
            break

    if not cashflow_item:
        return []

    # 3️⃣ 연도 헤더 (TTM 제외)
    # thead th strong
    raw_years_cols = cashflow_item.select("table.table-striped-col thead th strong")
    raw_years = [th.text.strip() for th in raw_years_cols]

    # 유효한 연도 인덱스 찾기 (TTM, 최근 12개월 제외)
    valid_indexes = []
    years = []
    
    for i, y in enumerate(raw_years):
        if "지난" not in y and "TTM" not in y:
            # 연도만 추출 (e.g., "2023" or "2023/12") -> 보통 YYYY
            # 한경은 "2023" 형태로 나옴
            valid_indexes.append(i)
            years.append(y.split("/")[0]) # "2024/09" -> "2024"

    # 4️⃣ 배당금 지급 (Cash Dividends Paid)
    # tr class 이름이 div_cf 인지 확인 필요.
    # User provided snippet says "tr.div_cf"
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
        # 배당 없는 경우 0으로 채움
        div_values = [0.0] * len(valid_indexes)

    # 5️⃣ 자본금 변동 (Repurchase of Capital Stock usually negative in CF)
    # User snippet says "tr.stk_chg_cf" -> "주식 발행(소각)"
    # 보통 자사주 매입은 음수로 표기됨.
    cap_row = cashflow_item.select_one("tr.stk_chg_cf")
    cap_values = []
    
    if cap_row:
        tds = cap_row.select("td")
        for i in valid_indexes:
            if i < len(tds):
                val = parse_number(tds[i].text)
                # 자사주 매입(Repurchase)은 현금 유출이므로 음수(-)
                # 하지만 DB에는 양수로 저장할지 음수로 저장할지 결정 필요.
                # fetch_us_cash_flow.py 기존 로직: gets absolute value or specific mapping?
                # yfinance scraper logic used abs() for some, but let's check mapping.
                # Previous script: `return val if allow_negative else abs(val)` -> used abs for repurchase likely.
                # Let's keep raw value for now, logic later handles sign.
                # Typically Repurchase is explicit.
                # 'stk_chg_cf' includes Issuance (positive) and Repurchase (negative).
                # We want Repurchase. If value is negative -> Repurchase. If positive -> Issuance.
                # DB column: `repurchase_of_capital_stock`.
                # If we want pure repurchase amount (positive magnitude), we should filter < 0.
                cap_values.append(val)
            else:
                cap_values.append(0.0)
    else:
        cap_values = [0.0] * len(valid_indexes)

    # 6️⃣ 결과 정리
    result = []
    for i, year in enumerate(years):
        # 자본금 변동이 음수면 -> 자사주 매입 (Repurchase) -> DB에는 양수로 저장 (매입액)
        # 자본금 변동이 양수면 -> 유상증자 (Issuance) -> 자사주 매입은 0
        
        repurchase_val = 0.0
        cap_change = cap_values[i]
        
        if cap_change < 0:
            repurchase_val = abs(cap_change) # 매입 규모
        
        # 배당금 지급도 현금 유출이므로 음수(-)로 표기됨. DB에는 양수로 저장 (지급액)
        dividend_paid_val = abs(div_values[i])

        result.append({
            "stac_yymm": f"{year}12", # 연간 데이터로 가정 (보통 12월 결산) - 한경은 연도만 나오므로 12월로 고정
            "div_code": "0", # Annual
            "repurchase_of_capital_stock": repurchase_val,
            "cash_dividends_paid": dividend_paid_val
        })

    return result

def save_cash_flows(stock_codes):
    """
    Hankyung -> DB Upsert
    """
    try:
        connection = pymysql.connect(**DB_CONFIG)
        
        for ticker in tqdm(stock_codes, desc="Fetching Cash Flows"):
            # Hankyung requests
            data_list = fetch_dividend_and_capital_change(ticker)
            
            if not data_list:
                time.sleep(0.1) # Basic wait
                continue
                
            with connection.cursor() as cursor:
                for row in data_list:
                    # Previous logic calculated shareholder_return_rate = (Repurchase + Dividend) / NetIncome * 100
                    # But here we don't have Net Income in this parser.
                    # We should NULL out shareholder_return_rate or fetch Net Income too.
                    # User request didn't mention Net Income fetching.
                    # BUT the table has `shareholder_return_rate`. 
                    # If we don't update it, it stays old.
                    # Let's set it to NULL or calculate later?
                    # Ideally we should fetch Net Income (Income Statement) to calculate it.
                    # For now, let's just save the raw amounts (Repurchase, Dividend).
                    # The previous script calculated it on the fly.
                    # If we want to maintain that field, we need Net Income.
                    # For this task, let's focus on saving the raw data as requested.
                    
                    sql = """
                    INSERT INTO overseas_stock_cash_flow 
                    (stock_code, stac_yymm, div_code, repurchase_of_capital_stock, cash_dividends_paid, shareholder_return_rate)
                    VALUES (%s, %s, %s, %s, %s, 0)
                    ON DUPLICATE KEY UPDATE
                    repurchase_of_capital_stock = VALUES(repurchase_of_capital_stock),
                    cash_dividends_paid = VALUES(cash_dividends_paid)
                    """
                    # shareholder_return_rate is set to 0 temporary or we preserve existing?
                    # The query updates repurchase and dividend. 
                    # Set 0 for now as we don't have NI.
                    
                    cursor.execute(sql, (
                        ticker, 
                        row['stac_yymm'], 
                        row['div_code'], 
                        row['repurchase_of_capital_stock'], 
                        row['cash_dividends_paid']
                    ))
            
            connection.commit()
            time.sleep(0.5) # Politeness delay
                
    except Exception as e:
        print(f"❌ DB connection error: {e}")
    finally:
        if 'connection' in locals():
            connection.close()

if __name__ == "__main__":
    print("🚀 Starting Shareholder Return (Cash Flow) update for US Stocks (Source: Hankyung)...")
    stocks = get_target_stocks()
    
    if not stocks:
        print("❌ No active US stocks found.")
    else:
        print(f"✅ Found {len(stocks)} active US stocks.")
        save_cash_flows(stocks)
        print("\n✨ Cash Flow update complete!")

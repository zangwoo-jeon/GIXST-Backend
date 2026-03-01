import pymysql
import requests
from bs4 import BeautifulSoup
from tqdm import tqdm
import time
import warnings
import sys
import os
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

# Suppress warnings
warnings.filterwarnings("ignore")
if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')

# DB Configuration
DB_CONFIG = {
    'host': os.environ.get('DB_HOST', '127.0.0.1'),
    'port': int(os.environ.get('DB_PORT', '3306')),
    'user': os.environ['DB_USER'],
    'password': os.environ['DB_PASSWORD'],
    'db': os.environ.get('DB_NAME', 'aisa_portfolio'),
    'charset': 'utf8',
    'cursorclass': pymysql.cursors.DictCursor
}

H_BASE_URL = os.environ.get('H_BASE_URL')

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
    if not text:
        return 0.0
    text = text.replace(",", "").strip()
    if text in ("-", ""):
        return 0.0
    try:
        return float(text)
    except ValueError:
        return 0.0

def extract_fcf_data(container, div_code):
    if not container:
        return []

    cashflow_item = None
    for item in container.select("div.item"):
        title = item.select_one("strong.module-stit")
        if title and "현금흐름표" in title.text.strip():
            cashflow_item = item
            break

    if not cashflow_item:
        return []

    raw_periods_cols = cashflow_item.select("table.table-striped-col thead th strong")
    raw_periods = [th.text.strip() for th in raw_periods_cols]

    valid_indexes = []
    periods = []
    
    for i, p in enumerate(raw_periods):
        if "기간" in p or "TTM" in p or "지난" in p or not p:
            continue
        clean_date = p.replace('/', '').replace('-', '').strip()
        
        formatted_yymm = ""
        if len(clean_date) >= 6:
            formatted_yymm = clean_date[:6]
        elif len(clean_date) == 4:
            formatted_yymm = f"{clean_date}12" 
            
        if formatted_yymm:
            valid_indexes.append(i)
            periods.append(formatted_yymm)

    # 잉여현금흐름 (FCF)
    fcf_values = []
    fcf_row = None
    
    # 텍스트로 '잉여현금흐름' 행 찾기
    for tr in cashflow_item.select("tbody tr"):
        th = tr.select_one("th")
        if th and "잉여현금흐름" in th.get_text(strip=True):
            # 행은 찾았지만 데이터(td)가 있는지 확인
            if tr.select("td"):
                fcf_row = tr
                break
    
    if fcf_row:
        tds = fcf_row.select("td")
        for i in valid_indexes:
            if i < len(tds):
                fcf_values.append(parse_number(tds[i].text))
            else:
                fcf_values.append(0.0)
    else:
        fcf_values = [0.0] * len(valid_indexes)

    result = []
    for i, yymm in enumerate(periods):
        fcf_val = fcf_values[i]
        
        result.append({
            "stac_yymm": yymm,
            "div_code": div_code,
            "fcf": fcf_val
        })
        
    return result

def fetch_fcf(stock_code):
    formatted_code = stock_code.lower().replace('.', '')
    url = f"{H_BASE_URL}/{formatted_code}"
    
    try:
        res = requests.get(url, headers=HEADERS, timeout=10)
        res.raise_for_status() 
    except Exception as e:
        print(f"⚠️ Failed to fetch {stock_code}: {e}")
        return []

    soup = BeautifulSoup(res.text, "html.parser")
    all_data = []

    # 1. Annual Data
    year_section = soup.select_one("div.finance-statements-year")
    if year_section:
        annual_data = extract_fcf_data(year_section, "0")
        all_data.extend(annual_data)

    # 2. Quarterly Data
    quarter_section = soup.select_one("div.finance-statements-quarter")
    if quarter_section:
        quarter_data = extract_fcf_data(quarter_section, "1")
        all_data.extend(quarter_data)

    return all_data

def save_fcf(stock_codes):
    try:
        connection = pymysql.connect(**DB_CONFIG)
        
        for ticker in tqdm(stock_codes, desc="Fetching FCF Only"):
            data_list = fetch_fcf(ticker)
            
            if not data_list:
                time.sleep(0.1) 
                continue
                
            with connection.cursor() as cursor:
                for row in data_list:
                    # FCF만 업데이트하도록 SQL 구성
                    # 만약 새로운 행이면 나머지는 NULL 또는 기본값
                    sql = """
                    INSERT INTO overseas_stock_cash_flow 
                    (stock_code, stac_yymm, div_code, fcf, shareholder_return_rate)
                    VALUES (%s, %s, %s, %s, 0)
                    ON DUPLICATE KEY UPDATE
                    fcf = VALUES(fcf)
                    """
                    
                    cursor.execute(sql, (
                        ticker, 
                        row['stac_yymm'], 
                        row['div_code'], 
                        row['fcf']
                    ))
            
            connection.commit()
            time.sleep(0.5) 
                
    except Exception as e:
        print(f"❌ DB connection error: {e}")
    finally:
        if 'connection' in locals():
            connection.close()

if __name__ == "__main__":
    print("🚀 Starting FCF Only update for US Stocks (Annual & Quarterly)...")
    stocks = get_target_stocks()
    
    if not stocks:
        print("❌ No active US stocks found.")
    else:
        print(f"✅ Found {len(stocks)} active US stocks.")
        save_fcf(stocks)
        print("\n✨ FCF update complete!")

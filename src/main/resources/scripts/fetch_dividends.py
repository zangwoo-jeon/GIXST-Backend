import pymysql
import requests
import json
import time
from tqdm import tqdm
import os
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

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

S_BASE_URL = os.environ.get('S_BASE_URL')

def to_s_ticker(ticker):
    return ticker.lower()

def get_target_stocks():
    """
    DB에서 모든 해외 주식 티커를 가져옵니다.
    """
    try:
        connection = pymysql.connect(**DB_CONFIG)
        with connection.cursor() as cursor:
            sql = "SELECT stock_id, stock_code FROM stock WHERE stock_type IN ('US_STOCK', 'US_ETF') AND (is_suspended IS NULL OR is_suspended = 0)"
            cursor.execute(sql)
            return cursor.fetchall()
    except Exception as e:
        print(f"❌ DB connection failed: {e}")
        return []
    finally:
        if 'connection' in locals():
            connection.close()

def fetch_and_save_dividends():
    stocks = get_target_stocks()
    if not stocks:
        print("❌ No active US stocks found.")
        return

    print(f"🚀 Found {len(stocks)} US stocks to process.")

    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cookie": os.environ.get('S_COOKIE', ''),
    }

    try:
        connection = pymysql.connect(**DB_CONFIG)
        with connection.cursor() as cursor:
            total_count = 0
            for stock_entry in tqdm(stocks, desc="Fetching Dividends"):
                stock_id = stock_entry['stock_id']
                ticker = stock_entry['stock_code']
                sa_ticker = to_s_ticker(ticker)
                
                # 티커별 동적 Referer 설정
                headers["Referer"] = f"{S_BASE_URL}/symbol/{ticker.upper()}/dividends/history"

                url = f"{S_BASE_URL}/api/v3/symbols/{sa_ticker}/dividend_history?years=5"
                try:
                    response = requests.get(url, headers=headers)
                    if response.status_code == 404:
                        continue # Skip if not found
                    
                    response.raise_for_status()
                    json_data = response.json()
                    items = json_data.get("data", [])

                    if not items:
                        continue

                    for entry in items:
                        attr = entry.get("attributes", {})
                        raw_record_date = attr.get("record_date") # yyyy-MM-dd
                        raw_pay_date = attr.get("pay_date")       # yyyy-MM-dd
                        amount = attr.get("amount")

                        if raw_record_date and raw_pay_date and amount:
                            # 포맷 변환 (yyyy-MM-dd -> yyyyMMdd / yyyy/MM/dd)
                            clean_record_date = raw_record_date.replace("-", "")
                            formatted_pay_date = raw_pay_date.replace("-", "/")

                            insert_sql = """
                            INSERT INTO stock_dividend (stock_id, record_date, payment_date, dividend_amount, dividend_rate, stock_price)
                            VALUES (%s, %s, %s, %s, 0.0, 0.0)
                            """
                            cursor.execute(insert_sql, (stock_id, clean_record_date, formatted_pay_date, amount))
                            total_count += 1

                    connection.commit()
                    time.sleep(1) # Rate limit 방지

                except Exception as e:
                    # print(f"⚠️ Error for {ticker}: {e}")
                    continue

        print(f"\n✨ Task complete! Total {total_count} dividend records saved.")

    except Exception as e:
        print(f"❌ Error occurred: {e}")
    finally:
        if 'connection' in locals():
            connection.close()

if __name__ == "__main__":
    fetch_and_save_dividends()

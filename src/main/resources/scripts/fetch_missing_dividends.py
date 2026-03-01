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

def get_missing_stocks(start_id=None, end_id=None):
    """
    배당 정보가 없는 해외 종목들만 조회합니다. (범위 지정 가능)
    """
    try:
        connection = pymysql.connect(**DB_CONFIG)
        with connection.cursor() as cursor:
            sql = """
            SELECT s.stock_id, s.stock_code
            FROM stock s
            LEFT JOIN stock_dividend sd ON s.stock_id = sd.stock_id
            WHERE sd.stock_id IS NULL 
              AND s.stock_type IN ('US_STOCK', 'US_ETF') 
              AND (s.is_suspended IS NULL OR s.is_suspended = 0)
            """
            params = []
            if start_id is not None and end_id is not None:
                sql += " AND s.stock_id BETWEEN %s AND %s"
                params = [start_id, end_id]
            
            sql += " ORDER BY s.stock_id ASC"
            
            cursor.execute(sql, params)
            return cursor.fetchall()
    except Exception as e:
        print(f"❌ DB connection failed: {e}")
        return []
    finally:
        if 'connection' in locals():
            connection.close()

def fetch_and_save_missing_dividends(start_id=None, end_id=None):
    stocks = get_missing_stocks(start_id, end_id)
    if not stocks:
        print("✅ No US stocks with missing dividend data found.")
        return

    print(f"🚀 Found {len(stocks)} US stocks with missing dividend data. Starting sync...")

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
            for stock_entry in tqdm(stocks, desc="Fetching Missing Dividends"):
                stock_id = stock_entry['stock_id']
                ticker = stock_entry['stock_code']
                sa_ticker = to_s_ticker(ticker)
                
                # 티커별 동적 Referer 설정
                headers["Referer"] = f"{S_BASE_URL}/symbol/{ticker.upper()}/dividends/history"

                url = f"{S_BASE_URL}/api/v3/symbols/{sa_ticker}/dividend_history?years=5"
                try:
                    response = requests.get(url, headers=headers)
                    if response.status_code == 404:
                        print(f"  - [{ticker}] Skip: Ticker not found (404)")
                        continue
                    if response.status_code == 403:
                        print(f"  - [{ticker}] CRITICAL: Access Blocked (403). Cookie might be expired.")
                        break # 403 발생 시 세션이 막힌 것이므로 중단하는 것이 좋음
                    
                    response.raise_for_status()
                    json_data = response.json()
                    items = json_data.get("data", [])

                    if not items:
                        print(f"  - [{ticker}] Skip: No dividend history found")
                        continue

                    ticker_count = 0
                    for entry in items:
                        attr = entry.get("attributes", {})
                        raw_record_date = attr.get("record_date")
                        raw_pay_date = attr.get("pay_date")
                        amount = attr.get("amount")

                        if raw_record_date and raw_pay_date and amount:
                            clean_record_date = raw_record_date.replace("-", "")
                            formatted_pay_date = raw_pay_date.replace("-", "/")

                            insert_sql = """
                            INSERT INTO stock_dividend (stock_id, record_date, payment_date, dividend_amount, dividend_rate, stock_price)
                            VALUES (%s, %s, %s, %s, 0.0, 0.0)
                            """
                            cursor.execute(insert_sql, (stock_id, clean_record_date, formatted_pay_date, amount))
                            ticker_count += 1
                            total_count += 1

                    connection.commit()
                    # print(f"  - [{ticker}] Success: {ticker_count} records saved")

                except Exception as e:
                    print(f"  - [{ticker}] Error: {str(e)}")
                    continue
                finally:
                    time.sleep(3) # 성공/실패 여부와 상관없이 무조건 3초 대기 ( Always-Sleep )

        print(f"\n✨ Task complete! Total {total_count} missing dividend records saved.")

    except Exception as e:
        print(f"❌ Error occurred: {e}")
    finally:
        if 'connection' in locals():
            connection.close()

if __name__ == "__main__":
    try:
        s_id = input("시작 stock_id (전체는 엔터): ").strip()
        e_id = input("종료 stock_id (전체는 엔터): ").strip()
        
        start_id = int(s_id) if s_id else None
        end_id = int(e_id) if e_id else None
        
        fetch_and_save_missing_dividends(start_id, end_id)
    except ValueError:
        print("❌ 올바른 숫자를 입력해 주세요.")

import pymysql
import requests
from bs4 import BeautifulSoup
from tqdm import tqdm
import time
from datetime import datetime

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

# Request Headers
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
}

def get_overseas_stock_list():
    try:
        connection = pymysql.connect(**DB_CONFIG)
        with connection.cursor() as cursor:
            sql = "SELECT stock_code FROM stock WHERE stock_type IN ('US_STOCK', 'US_ETF')"
            cursor.execute(sql)
            result = cursor.fetchall()
            return [row['stock_code'] for row in result]
    except Exception as e:
        print(f"❌ DB connection failed: {e}")
        return []
    finally:
        if 'connection' in locals():
            connection.close()

def fetch_ev_ebitda_from_hankyung(symbol):
    """Hankyung Global Market에서 EV/EBITDA를 추출"""
    try:
        # handle special cases like 'BRK.A' -> 'brka'
        formatted_code = symbol.lower().replace('.', '')
        url = f"https://www.hankyung.com/globalmarket/equities/americas/{formatted_code}"
        
        res = requests.get(url, headers=HEADERS, timeout=10)
        if res.status_code != 200:
            return None
        
        soup = BeautifulSoup(res.text, 'html.parser')
        
        # 연간 재무제표 컨테이너 찾기
        container = soup.find('div', class_='finance-statements-year')
        if not container:
            return None
            
        table = container.find('table', class_='table-striped-col')
        if not table:
            return None
            
        # EV/EBITDA 행 찾기
        ev_ebitda_row = None
        for tr in table.find_all('tr'):
            if 'EV/EBITDA' in tr.get_text():
                ev_ebitda_row = tr
                break
                
        if not ev_ebitda_row:
            return None
            
        # 값 선택 (가장 최근의 유효한 값 선택 - 오른쪽에서 왼쪽으로)
        # cells: ['EV/EBITDA', 'val2022', 'val2023', 'val2024(E)', ...]
        cells = [c.get_text(strip=True).replace(',', '') for c in ev_ebitda_row.find_all(['td', 'th'])]
        
        # 오른쪽(최신 데이터)부터 확인하여 유효한 숫자 선택
        for val in reversed(cells[1:]):
            if val and val != '-' and val != '0':
                try:
                    return float(val)
                except ValueError:
                    continue
        return None
    except Exception as e:
        return None

def main():
    print("🚀 Starting EV/EBITDA update from Hankyung...")
    
    stocks = get_overseas_stock_list()
    if not stocks:
        print("❌ No stocks found.")
        return

    print(f"✅ Found {len(stocks)} stocks.")
    
    connection = pymysql.connect(**DB_CONFIG)
    try:
        cursor = connection.cursor()
        
        for symbol in tqdm(stocks, desc="Fetching EV/EBITDA"):
            ev_ebitda = fetch_ev_ebitda_from_hankyung(symbol)
            
            if ev_ebitda is None:
                continue

            # Upsert SQL (Only ev_ebitda)
            check_sql = "SELECT id FROM overseas_stock_trading_multiple WHERE stock_code = %s"
            cursor.execute(check_sql, (symbol,))
            existing = cursor.fetchone()

            now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

            if existing:
                sql = """
                    UPDATE overseas_stock_trading_multiple
                    SET ev_ebitda = %s, last_updated = %s
                    WHERE stock_code = %s
                """
                cursor.execute(sql, (ev_ebitda, now, symbol))
            else:
                sql = """
                    INSERT INTO overseas_stock_trading_multiple (stock_code, ev_ebitda, last_updated)
                    VALUES (%s, %s, %s)
                """
                cursor.execute(sql, (symbol, ev_ebitda, now))
            
            connection.commit()
            time.sleep(0.5) # Rate limit prevention

    except Exception as e:
        print(f"❌ Error: {e}")
    finally:
        connection.close()
    
    print("\n✨ Update complete!")

if __name__ == "__main__":
    main()

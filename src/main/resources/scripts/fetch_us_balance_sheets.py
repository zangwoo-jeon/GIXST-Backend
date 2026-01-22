import pymysql
import yfinance as yf
import pandas as pd
from tqdm import tqdm
import time

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

def get_overseas_stock_list():
    try:
        connection = pymysql.connect(**DB_CONFIG)
        with connection.cursor() as cursor:
            sql = "SELECT stock_code FROM stock WHERE stock_type = 'US_STOCK'"
            cursor.execute(sql)
            result = cursor.fetchall()
            return [row['stock_code'] for row in result]
    except Exception as e:
        print(f"❌ DB connection failed: {e}")
        return []
    finally:
        if 'connection' in locals():
            connection.close()

def save_balance_sheets(stock_codes):
    """
    yfinance에서 재무상태표(Balance Sheet) 정보를 가져와 DB에 직접 저장합니다.
    중복 발생 시 최신 데이터로 덮어씁니다.
    """
    try:
        connection = pymysql.connect(**DB_CONFIG)
        
        for ticker in tqdm(stock_codes, desc="Fetching Balance Sheets"):
            try:
                stock = yf.Ticker(ticker)
                
                # Balance Sheet 데이터 (자산, 부채, 자본) - 연간 데이터만 사용
                annual_bs = stock.balance_sheet.T.head(3)
                
                if annual_bs.empty:
                    continue

                with connection.cursor() as cursor:
                    # Annual 데이터 저장 (div_code '0')
                    process_and_save_bs(cursor, ticker, annual_bs, '0')
                
                connection.commit()
                time.sleep(0.5) # Rate limit
                
            except Exception as e:
                print(f"⚠️ Error processing {ticker}: {e}")
                continue
                
    except Exception as e:
        print(f"❌ DB connection error: {e}")
    finally:
        if 'connection' in locals():
            connection.close()

def process_and_save_bs(cursor, ticker, df, div_code):
    if df.empty: return
    
    for date, row in df.iterrows():
        if pd.isna(date): continue
        stac_yymm = date.strftime('%Y%m')
        
        # yfinance 항목 매핑
        assets = get_val(row, ['Total Assets'], 0)
        liabilities = get_val(row, ['Total Liabilities Net Minority Interest', 'Total Liabilities'], 0)
        equity = get_val(row, ['Total Equity Gross Minority Interest', 'Stockholders Equity'], 0)
        
        # Million Dollars로 변환
        assets_m = round(assets / 1000000)
        liabilities_m = round(liabilities / 1000000)
        equity_m = round(equity / 1000000)
        
        # ON DUPLICATE KEY UPDATE 문법을 사용하여 덮어쓰기 적용
        sql = """
        INSERT INTO overseas_stock_balance_sheet 
        (stock_code, stac_yymm, div_code, total_assets, total_liabilities, total_capital)
        VALUES (%s, %s, %s, %s, %s, %s)
        ON DUPLICATE KEY UPDATE
        total_assets = VALUES(total_assets),
        total_liabilities = VALUES(total_liabilities),
        total_capital = VALUES(total_capital)
        """
        cursor.execute(sql, (ticker, stac_yymm, div_code, assets_m, liabilities_m, equity_m))

def get_val(row, keys, default=0):
    for key in keys:
        if key in row and not pd.isna(row[key]):
            return row[key]
    return default

if __name__ == "__main__":
    print("🚀 Starting Balance Sheet update for US Stocks (Direct DB)...")
    stocks = get_overseas_stock_list()
    
    if not stocks:
        print("❌ No stocks found.")
    else:
        print(f"✅ Found {len(stocks)} stocks.")
        save_balance_sheets(stocks)
        print("\n✨ Balance Sheet update complete!")

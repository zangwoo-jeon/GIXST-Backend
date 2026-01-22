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

def save_financial_data(stock_codes):
    """
    yfinance에서 손익계산서(Income Statement) 정보를 가져와 DB에 직접 저장합니다.
    중복 발생 시 최신 데이터로 덮어씁니다.
    """
    try:
        connection = pymysql.connect(**DB_CONFIG)
        
        for ticker in tqdm(stock_codes, desc="Fetching Financials"):
            try:
                stock = yf.Ticker(ticker)
                
                # Fetch Data
                annual_df = stock.financials.T.head(3)
                quarterly_df = stock.quarterly_financials.T.head(5)
                
                with connection.cursor() as cursor:
                    # Annual
                    process_and_save_is(cursor, ticker, annual_df, '0')
                    # Quarterly
                    process_and_save_is(cursor, ticker, quarterly_df, '1')
                
                connection.commit()
                time.sleep(0.5) # Rate limit prevention
                
            except Exception as e:
                print(f"⚠️ Error processing {ticker}: {e}")
                continue
                
    except Exception as e:
        print(f"❌ DB connection error: {e}")
    finally:
        if 'connection' in locals():
            connection.close()

def process_and_save_is(cursor, ticker, df, div_code):
    if df.empty: return
    
    for date, row in df.iterrows():
        if pd.isna(date): continue
        stac_yymm = date.strftime('%Y%m')
        
        rev = get_val(row, ['Total Revenue'], 0)
        op_inc = get_val(row, ['Operating Income'], 0)
        net_inc = get_val(row, ['Net Income Common Stockholders', 'Net Income'], 0)
        
        # Million Dollars로 변환
        rev_m = round(rev / 1000000)
        op_inc_m = round(op_inc / 1000000)
        net_inc_m = round(net_inc / 1000000)
        
        # ON DUPLICATE KEY UPDATE 문법을 사용하여 덮어쓰기 적용
        sql = """
        INSERT INTO overseas_stock_financial_statement 
        (stock_code, stac_yymm, div_code, total_revenue, operating_income, net_income, is_suspended)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
        ON DUPLICATE KEY UPDATE
        total_revenue = VALUES(total_revenue),
        operating_income = VALUES(operating_income),
        net_income = VALUES(net_income),
        is_suspended = VALUES(is_suspended)
        """
        cursor.execute(sql, (ticker, stac_yymm, div_code, rev_m, op_inc_m, net_inc_m, False))

def get_val(row, keys, default=0):
    for key in keys:
        if key in row and not pd.isna(row[key]):
            return row[key]
    return default

if __name__ == "__main__":
    print("🚀 Starting Income Statement update for US Stocks (Direct DB)...")
    stocks = get_overseas_stock_list()
    
    if not stocks:
        print("❌ No stocks found.")
    else:
        print(f"✅ Found {len(stocks)} stocks.")
        save_financial_data(stocks)
        print("\n✨ Financial data update complete!")

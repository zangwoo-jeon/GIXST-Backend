import pymysql
import yfinance as yf
import pandas as pd
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

def fetch_and_save_metrics(stock_codes):
    try:
        connection = pymysql.connect(**DB_CONFIG)
        cursor = connection.cursor()
        
        for symbol in tqdm(stock_codes, desc="Fetching Yahoo Metrics"):
            try:
                ticker = yf.Ticker(symbol)
                info = ticker.info
                
                # 1. PEG Ratio
                peg_ratio = info.get('pegRatio')
                if peg_ratio is None:
                    peg_ratio = info.get('trailingPegRatio')

                # PEG Fallback Calculation
                if peg_ratio is None:
                    f_pe = info.get('forwardPE')
                    eg = info.get('earningsGrowth') or info.get('longTermAverageGrowthRate') # e.g., 0.25 for 25%

                    if f_pe and eg and eg != 0:
                        # Growth rate normalization: if < 1 (e.g., 0.25), multiply by 100 to get 25.
                        # If > 1 (unlikely for raw decimal, but safety), usually it's already percentage? 
                        # yfinance usually returns 0.15 for 15%.
                        growth_val = eg * 100 if abs(eg) < 1.0 else eg 
                        # Use absolute value for growth? No, negative growth makes PEG negative or invalid.
                        if growth_val != 0:
                            peg_ratio = round(f_pe / growth_val, 2)
                
                # 2. EV/EBITDA
                ev_ebitda = info.get('enterpriseToEbitda') # float

                # Prepare values for SQL
                peg_val = peg_ratio if peg_ratio is not None else "NULL"
                ev_ebitda_val = ev_ebitda if ev_ebitda is not None else "NULL"
                
                if peg_val == "NULL" and ev_ebitda_val == "NULL":
                    continue # Skip if no data

                # Upsert SQL
                check_sql = "SELECT id FROM overseas_stock_trading_multiple WHERE stock_code = %s"
                cursor.execute(check_sql, (symbol,))
                existing = cursor.fetchone()

                now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

                if existing:
                    sql = """
                        UPDATE overseas_stock_trading_multiple
                        SET peg_ratio = %s, ev_ebitda = %s, last_updated = %s
                        WHERE stock_code = %s
                    """
                    cursor.execute(sql, (peg_ratio, ev_ebitda, now, symbol))
                else:
                    sql = """
                        INSERT INTO overseas_stock_trading_multiple (stock_code, peg_ratio, ev_ebitda, last_updated)
                        VALUES (%s, %s, %s, %s)
                    """
                    cursor.execute(sql, (symbol, peg_ratio, ev_ebitda, now))
                
                connection.commit()
                # time.sleep(0.5) # Rate limit prevention (Adjust as needed)

            except Exception as e:
                print(f"⚠️ Error processing {symbol}: {e}")
                continue
                
    except Exception as e:
        print(f"❌ DB connection error: {e}")
    finally:
        if 'connection' in locals():
            connection.close()

if __name__ == "__main__":
    print("🚀 Starting Yahoo Finance Metrics Update (PEG, EV/EBITDA)...")
    
    # 1. Get Stock List
    stocks = get_overseas_stock_list()
    # DEBUG: Test with small list
    # stocks = ["AAPL", "MSFT", "GOOGL", "NVDA", "TSLA"] 
    
    if not stocks:
        print("❌ No stocks found.")
    else:
        print(f"✅ Found {len(stocks)} stocks.")
        fetch_and_save_metrics(stocks)
        print("\n✨ Update complete!")

import pymysql
import yfinance as yf
from tqdm import tqdm
import time
from datetime import datetime
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

def fetch_and_save_metrics(stock_codes):
    try:
        connection = pymysql.connect(**DB_CONFIG)
        cursor = connection.cursor()
        
        for symbol in tqdm(stock_codes, desc="Fetching Yahoo PEG"):
            try:
                # Yahoo Finance uses '-' instead of '.' for classes (e.g., BRK.A -> BRK-A)
                yahoo_symbol = symbol.replace('.', '-')
                ticker = yf.Ticker(yahoo_symbol)
                info = ticker.info
                
                # PEG Ratio Extraction
                peg_ratio = info.get('pegRatio')
                if peg_ratio is None:
                    peg_ratio = info.get('trailingPegRatio')

                # PEG Fallback Calculation
                if peg_ratio is None:
                    f_pe = info.get('forwardPE')
                    eg = info.get('earningsGrowth') or info.get('longTermAverageGrowthRate') # e.g., 0.25 for 25%

                    if f_pe and eg and eg != 0:
                        # Growth rate normalization: if < 1 (e.g., 0.25), multiply by 100 to get 25.
                        growth_val = eg * 100 if abs(eg) < 1.0 else eg 
                        if growth_val != 0:
                            peg_ratio = round(f_pe / growth_val, 2)
                
                if peg_ratio is None:
                    continue # Skip if no data

                # Upsert SQL (Only peg_ratio)
                check_sql = "SELECT id FROM overseas_stock_trading_multiple WHERE stock_code = %s"
                cursor.execute(check_sql, (symbol,))
                existing = cursor.fetchone()

                now = datetime.now().strftime('%Y-%m-%d %H:%M:%S')

                if existing:
                    sql = """
                        UPDATE overseas_stock_trading_multiple
                        SET peg_ratio = %s, last_updated = %s
                        WHERE stock_code = %s
                    """
                    cursor.execute(sql, (peg_ratio, now, symbol))
                else:
                    sql = """
                        INSERT INTO overseas_stock_trading_multiple (stock_code, peg_ratio, last_updated)
                        VALUES (%s, %s, %s)
                    """
                    cursor.execute(sql, (symbol, peg_ratio, now))
                
                connection.commit()

            except Exception as e:
                print(f"⚠️ Error processing {symbol}: {e}")
                continue
                
    except Exception as e:
        print(f"❌ DB connection error: {e}")
    finally:
        if 'connection' in locals():
            connection.close()

if __name__ == "__main__":
    print("🚀 Starting Yahoo Finance PEG Update...")
    
    stocks = get_overseas_stock_list()
    
    if not stocks:
        print("❌ No stocks found.")
    else:
        print(f"✅ Found {len(stocks)} stocks.")
        fetch_and_save_metrics(stocks)
        print("\n✨ Update complete!")

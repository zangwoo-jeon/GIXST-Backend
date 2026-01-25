import pymysql
import yfinance as yf
import pandas as pd
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

def save_cash_flows(stock_codes):
    """
    yfinance for Cash Flow (Repurchase, Dividend) -> DB Upsert
    """
    try:
        connection = pymysql.connect(**DB_CONFIG)
        
        for ticker in tqdm(stock_codes, desc="Fetching Cash Flows"):
            try:
                stock = yf.Ticker(ticker)
                
                # 1. Fetch Data
                try:
                    annual_cf = stock.cashflow
                    quarterly_cf = stock.quarterly_cashflow
                except Exception:
                    # yfinance specific errors
                    continue

                if annual_cf.empty and quarterly_cf.empty:
                    continue

                # Fetch Income Statement for Net Income
                try:
                    annual_is = stock.financials
                    quarterly_is = stock.quarterly_financials
                except Exception:
                    annual_is = pd.DataFrame()
                    quarterly_is = pd.DataFrame()

                # Limit data points (last 4 years / 5 quarters)
                if not annual_cf.empty:
                    annual_cf = annual_cf.iloc[:, :4]
                if not quarterly_cf.empty:
                    quarterly_cf = quarterly_cf.iloc[:, :5]

                with connection.cursor() as cursor:
                    # 2. Process & Save Annual (div_code '0')
                    process_and_save_cf(cursor, ticker, annual_cf, annual_is, '0')
                    
                    # 3. Process & Save Quarterly (div_code '1')
                    process_and_save_cf(cursor, ticker, quarterly_cf, quarterly_is, '1')
                
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

def process_and_save_cf(cursor, ticker, df_cf, df_is, div_code):
    if df_cf.empty: return
    
    mapping = {
        'repurchase': ['Repurchase Of Capital Stock', 'Common Stock Repurchase', 'Repurchase Of Stock'],
        'dividend': ['Cash Dividends Paid', 'Common Stock Dividend Payments', 'Dividends Paid']
    }
    
    is_mapping = {
        'net_income': ['Net Income', 'Net Income Common Stockholders']
    }

    for date in df_cf.columns:
        if pd.isna(date): continue
        
        try:
            stac_yymm = date.strftime('%Y%m')
        except AttributeError:
            continue # If column name is not a date object

        # 2022년 이후 데이터만 저장 (안정성 확보)
        if int(stac_yymm[:4]) < 2022:
            continue

        row_data = df_cf[date]

        def get_value(row, keys, allow_negative=False):
            for k in keys:
                if k in row.index:
                    val = row[k]
                    if pd.notna(val):
                        val = float(val)
                        return val if allow_negative else abs(val)
            return 0.0

        repurchase_val = get_value(row_data, mapping['repurchase'])
        dividend_val = get_value(row_data, mapping['dividend'])

        # Calculate Shareholder Return Rate
        shareholder_return_rate = 0.0
        
        # Find matching Net Income from Income Statement
        net_income_val = 0.0
        if not df_is.empty:
            # Try to find exact date match
            if date in df_is.columns:
                 row_is = df_is[date]
                 net_income_val = get_value(row_is, is_mapping['net_income'], allow_negative=True)
            else:
                # If exact date mismatch, try finding closest date within 7 days
                # (Sometimes CF and IS dates differ slightly in API)
                for d in df_is.columns:
                    try:
                        delta = abs((date - d).days)
                        if delta <= 7:
                            row_is = df_is[d]
                            net_income_val = get_value(row_is, is_mapping['net_income'], allow_negative=True)
                            break
                    except:
                        pass
        
        # Method B: 적자여도 그대로 계산하여 저장 (데이터 보존)
        # 분석 시 주주환원율이 음수이면 "적자 상태에서의 배당"으로 해석하거나 0으로 조정하여 사용
        if net_income_val != 0:
            shareholder_return_rate = ((repurchase_val + dividend_val) / net_income_val) * 100
            shareholder_return_rate = round(shareholder_return_rate, 2)
        else:
            shareholder_return_rate = 0.0

        # 값이 모두 0이면 저장하지 않음 (불필요 데이터 방지)
        if repurchase_val == 0 and dividend_val == 0:
            continue

        # ON DUPLICATE KEY UPDATE
        sql = """
        INSERT INTO overseas_stock_cash_flow 
        (stock_code, stac_yymm, div_code, repurchase_of_capital_stock, cash_dividends_paid, shareholder_return_rate)
        VALUES (%s, %s, %s, %s, %s, %s)
        ON DUPLICATE KEY UPDATE
        repurchase_of_capital_stock = VALUES(repurchase_of_capital_stock),
        cash_dividends_paid = VALUES(cash_dividends_paid),
        shareholder_return_rate = VALUES(shareholder_return_rate)
        """
        cursor.execute(sql, (ticker, stac_yymm, div_code, repurchase_val, dividend_val, shareholder_return_rate))

if __name__ == "__main__":
    print("🚀 Starting Shareholder Return (Cash Flow) update for US Stocks...")
    stocks = get_target_stocks()
    
    if not stocks:
        print("❌ No active US stocks found.")
    else:
        print(f"✅ Found {len(stocks)} active US stocks.")
        save_cash_flows(stocks)
        print("\n✨ Cash Flow update complete!")

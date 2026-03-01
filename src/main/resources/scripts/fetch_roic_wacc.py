import pymysql
import requests
from bs4 import BeautifulSoup
from tqdm import tqdm
import time
import pandas as pd
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

H_BASE_URL = os.environ.get('H_BASE_URL')

# Request Headers
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
}

def get_overseas_stock_list():
    try:
        connection = pymysql.connect(**DB_CONFIG)
        with connection.cursor() as cursor:
            sql = "SELECT stock_code FROM stock WHERE stock_type IN ('US_STOCK')"
            cursor.execute(sql)
            result = cursor.fetchall()
            return [row['stock_code'] for row in result]
    except Exception as e:
        print(f"❌ DB connection failed: {e}")
        return []
    finally:
        if 'connection' in locals():
            connection.close()

def extract_financial_data(container, section_title):
    """HTML 컨테이너에서 데이터 추출"""
    if not container:
        return None
        
    items = container.find_all('div', class_='item')
    target_table = None
    
    for item in items:
        title_tag = item.find('strong', class_='module-stit')
        if title_tag and section_title in title_tag.get_text(strip=True):
            target_table = item.find('table', class_='table-striped-col')
            break
            
    if not target_table:
        return None

    result = {}
    thead = target_table.find('thead')
    if thead:
        dates = []
        for th in thead.find_all('th'):
            text = th.get_text(strip=True)
            if not text or text in ["기간", "주요재무표", "주요재무제표"]: continue
            dates.append(text)
        result['periods'] = dates

    tbody = target_table.find('tbody')
    if tbody:
        rows = tbody.find_all('tr')
        for row in rows:
            th = row.find('th')
            if not th: continue
            label = th.get_text(strip=True)
            values = []
            for td in row.find_all('td'):
                val = td.get_text(strip=True).replace(',', '')
                values.append("0" if not val or val == '-' else val)
            result[label] = values
    return result

def save_roic_wacc_data(connection, stock_code, is_data, bs_data, cf_data, div_code):
    if not is_data or 'periods' not in is_data: return

    periods = is_data['periods']
    with connection.cursor() as cursor:
        for i, raw_date in enumerate(periods):
            stac_yymm = raw_date.replace('-', '').replace('/', '')[:6]
            if not stac_yymm.isdigit(): continue

            # 손익계산서 업데이트
            pretax = is_data.get('법인세 차감 전 이익', ["0"]*10)[i]
            tax = is_data.get('법인세', ["0"]*10)[i]
            interest = is_data.get('이자비용', ["0"]*10)[i]
            
            sql_is = """
                UPDATE overseas_stock_financial_statement 
                SET pretax_income = %s, income_tax = %s, interest_expense = %s
                WHERE stock_code = %s AND stac_yymm = %s AND div_code = %s
            """
            cursor.execute(sql_is, (pretax, tax, interest, stock_code, stac_yymm, div_code))

            # 재무상태표 업데이트
            if bs_data and i < len(bs_data.get('유동자산', [])):
                curr_assets = bs_data.get('유동자산', ["0"]*10)[i]
                cash = bs_data.get('현금&현금성자산', ["0"]*10)[i]
                curr_liab = bs_data.get('유동부채', ["0"]*10)[i]
                
                sql_bs = """
                    UPDATE overseas_stock_balance_sheet 
                    SET current_assets = %s, cash_and_equivalents = %s, current_liabilities = %s
                    WHERE stock_code = %s AND stac_yymm = %s AND div_code = %s
                """
                cursor.execute(sql_bs, (curr_assets, cash, curr_liab, stock_code, stac_yymm, div_code))

            # 현금흐름표 업데이트
            if cf_data and i < len(cf_data.get('감가상각비', [])):
                depre = cf_data.get('감가상각비', ["0"]*10)[i]
                
                sql_cf = """
                    UPDATE overseas_stock_cash_flow 
                    SET depreciation_amortization = %s
                    WHERE stock_code = %s AND stac_yymm = %s AND div_code = %s
                """
                cursor.execute(sql_cf, (depre, stock_code, stac_yymm, div_code))

def main():
    print("🚀 Starting Specialized ROIC/WACC Data Update...")
    stocks = get_overseas_stock_list()
    if not stocks: return

    connection = pymysql.connect(**DB_CONFIG)
    try:
        for stock_code in tqdm(stocks, desc="Processing"):
            try:
                formatted_code = stock_code.lower().replace('.', '')
                url = f"{H_BASE_URL}/{formatted_code}"
                res = requests.get(url, headers=HEADERS, timeout=10)
                if res.status_code != 200: continue
                
                soup = BeautifulSoup(res.text, 'html.parser')
                y_container = soup.find('div', class_='finance-statements-year')
                if not y_container: continue

                is_data = extract_financial_data(y_container, '손익계산서')
                bs_data = extract_financial_data(y_container, '재무상태표')
                cf_data = extract_financial_data(y_container, '현금흐름표')

                if is_data:
                    save_roic_wacc_data(connection, stock_code, is_data, bs_data, cf_data, '0')
                
                connection.commit()
                time.sleep(0.3)
            except Exception as e:
                print(f"⚠️ Error processing {stock_code}: {e}")
    finally:
        connection.close()

if __name__ == "__main__":
    main()

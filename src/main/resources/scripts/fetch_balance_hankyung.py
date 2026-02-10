import pymysql
import requests
from bs4 import BeautifulSoup
from tqdm import tqdm
import time
import pandas as pd

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
            # US_STOCK 전체 조회
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

def extract_balance_sheet(container):
    """HTML 컨테이너에서 '재무상태표' 데이터를 추출하여 딕셔너리로 반환"""
    if not container:
        return None
        
    items = container.find_all('div', class_='item')
    target_table = None
    
    # '재무상태표' 섹션 찾기
    for item in items:
        title_tag = item.find('strong', class_='module-stit')
        if title_tag and '재무상태표' in title_tag.get_text(strip=True):
            target_table = item.find('table', class_='table-striped-col')
            break
            
    if not target_table:
        return None

    result = {}
    
    # 날짜 추출 (thead)
    thead = target_table.find('thead')
    if thead:
        dates = []
        for th in thead.find_all('th'):
            text = th.get_text(strip=True)
            if not text or text == "기간" or text == "주요재무표":
                continue
            dates.append(text)
        result['periods'] = dates

    # 항목별 수치 추출 (tbody)
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
                # '-' or empty -> 0
                if not val or val == '-':
                    values.append("0")
                else:
                    values.append(val)
            result[label] = values

    return result

def save_bs_to_db(connection, stock_code, data, div_code):
    """
    추출된 재무상태표 데이터를 DB에 저장 (UPSERT)
    div_code: 0 (연간), 1 (분기)
    """
    if not data or 'periods' not in data:
        return

    periods = data['periods']
    
    # 항목 매핑 (한경 vs DB/변수)
    # 총자산, 총부채, 총자본
    assets = data.get('총자산', [])
    liabilities = data.get('총부채', [])
    capital = data.get('총자본', [])

    with connection.cursor() as cursor:
        for i, raw_date in enumerate(periods):
            if i >= len(assets): break
            
            # 날짜 파싱 (2024-12-31 or 2024/12/31 etc)
            stac_yymm = raw_date.replace('-', '').replace('/', '')
            
            # YYYYMM 형태로 맞춤 (6자리)
            if len(stac_yymm) > 6:
                stac_yymm = stac_yymm[:6]
            
            # 유효성 체크
            if not stac_yymm.isdigit():
                continue
                
            # 2021년 이전 데이터 스킵 (선택 사항 - 데이터 가용성 고려)
            if int(stac_yymm[:4]) < 2021:
                continue

            # 사용자 요청: 분기 데이터 중 202406 데이터는 제거
            if div_code == '1' and stac_yymm == '202406':
                continue

            asset = assets[i]
            liab = liabilities[i]
            cap = capital[i]

            # DB Insert
            sql = """
                INSERT INTO overseas_stock_balance_sheet 
                (stock_code, stac_yymm, div_code, total_assets, total_liabilities, total_capital)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                total_assets = VALUES(total_assets),
                total_liabilities = VALUES(total_liabilities),
                total_capital = VALUES(total_capital)
            """
            cursor.execute(sql, (stock_code, stac_yymm, div_code, asset, liab, cap))

def main():
    print("🚀 Starting Balance Sheet update from Hankyung...")
    
    # 1. 대상 종목 가져오기
    stocks = get_overseas_stock_list()
    # stocks = ['TSM', 'KB'] # Test
    
    if not stocks:
        print("❌ No stocks found.")
        return

    connection = pymysql.connect(**DB_CONFIG)
    
    try:
        for stock_code in tqdm(stocks, desc="Processing"):
            try:
                # URL 생성 (대부분 americas로 가정)
                # handle special cases like 'BRK.A' -> 'brka' for Hankyung URL
                formatted_code = stock_code.lower().replace('.', '')
                url = f"https://www.hankyung.com/globalmarket/equities/americas/{formatted_code}"
                
                res = requests.get(url, headers=HEADERS, timeout=10)
                if res.status_code != 200:
                    continue
                
                soup = BeautifulSoup(res.text, 'html.parser')

                # 연간/분기 컨테이너 찾기
                q_container = soup.find('div', class_='finance-statements-quarter')
                y_container = soup.find('div', class_='finance-statements-year')

                # 재무상태표 추출
                # bs_q = extract_balance_sheet(q_container)
                bs_y = extract_balance_sheet(y_container)

                if bs_y:
                    save_bs_to_db(connection, stock_code, bs_y, '0') # 연간
                # if bs_q:
                #     save_bs_to_db(connection, stock_code, bs_q, '1') # 분기
                
                connection.commit()
                time.sleep(0.5)
                
            except Exception as e:
                print(f"⚠️ Error processing {stock_code}: {e}")
                
    finally:
        connection.close()
    
    print("✨ Update complete!")

if __name__ == "__main__":
    main()

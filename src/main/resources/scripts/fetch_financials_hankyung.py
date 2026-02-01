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

def extract_financial_table(container):
    """HTML 컨테이너에서 재무 데이터를 추출하여 딕셔너리로 반환"""
    if not container:
        return None

    data_table = container.find('table', class_='table-striped-col')
    if not data_table:
        return None

    result = {}
    
    # 날짜 추출 (thead)
    thead = data_table.find('thead')
    if thead:
        # 첫 번째 th는 "기간" 또는 공백일 수 있으므로 제외하고 날짜만 추출
        dates = []
        for th in thead.find_all('th'):
            text = th.get_text(strip=True)
            if not text or text == "기간" or text == "주요재무표":
                continue
            dates.append(text)
        result['periods'] = dates

    # 항목별 수치 추출 (tbody)
    tbody = data_table.find('tbody')
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

def save_to_db(connection, stock_code, data, div_code):
    """
    추출된 데이터를 DB에 저장 (UPSERT)
    div_code: 0 (연간), 1 (분기)
    """
    if not data or 'periods' not in data:
        return

    periods = data['periods']
    
    # 각 항목별 데이터 리스트 가져오기 (없으면 빈 리스트)
    revenues = data.get('매출액', [])
    op_incomes = data.get('영업이익', [])
    net_incomes = data.get('순이익', []) # HANKYUNG에서는 '순이익' 또는 '당기순이익' 확인 필요. 보통 '순이익'

    # 데이터 길이 불일치 방지 (dates 길이만큼 loop)
    # 데이터 길이 불일치 방지 (dates 길이만큼 loop)
    # TSM 페이지 확인 결과: 왼쪽(2021/12) -> 오른쪽(2024/12). 즉 오름차순.
    
    with connection.cursor() as cursor:
        for i, raw_date in enumerate(periods):
            if i >= len(revenues): break
            
            # 날짜 파싱 (2024-12-31 or 2024/12/31 etc)
            # Hankyung usually: 2021/12, 2022/12 ... (Monthly implied?) or 2024-12-31
            # User example: 2024-12-31
            stac_yymm = raw_date.replace('-', '').replace('/', '')
            
            # YYYYMM 형태로 맞춤 (6자리)
            if len(stac_yymm) > 6:
                stac_yymm = stac_yymm[:6]
            
            # 유효성 체크
            if not stac_yymm.isdigit():
                continue
                
            # 2021년 이전 데이터 스킵 (선택 사항)
            if int(stac_yymm[:4]) < 2021:
                continue

            # 사용자 요청: 분기 데이터 중 202406 데이터는 제거 (데이터 부재/오류로 추정)
            if div_code == '1' and stac_yymm == '202406':
                continue

            rev = revenues[i]
            op_inc = op_incomes[i]
            net_inc = net_incomes[i]

            # DB Insert
            sql = """
                INSERT INTO overseas_stock_financial_statement 
                (stock_code, stac_yymm, div_code, total_revenue, operating_income, net_income, is_suspended)
                VALUES (%s, %s, %s, %s, %s, %s, 0)
                ON DUPLICATE KEY UPDATE
                total_revenue = VALUES(total_revenue),
                operating_income = VALUES(operating_income),
                net_income = VALUES(net_income),
                is_suspended = VALUES(is_suspended)
            """
            cursor.execute(sql, (stock_code, stac_yymm, div_code, rev, op_inc, net_inc))

def main():
    print("🚀 Starting Financials update from Hankyung...")
    
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
                    # print(f"⚠️ Failed to fetch {stock_code}: Status {res.status_code}")
                    continue
                
                soup = BeautifulSoup(res.text, 'html.parser')

                # 연간/분기 컨테이너 찾기
                # User provided classes: finance-statements-quarter, finance-statements-year
                # 그러나 사이트 구조 변경 가능성 있음. 
                # User script uses: 
                # q_container = soup.find('div', class_='finance-statements-quarter')
                # y_container = soup.find('div', class_='finance-statements-year')
                
                # 실제 한경 글로벌마켓 페이지 소스 구조 확인 필요하나, 유저 제공 코드 신뢰.
                q_container = soup.find('div', class_='finance-statements-quarter')
                y_container = soup.find('div', class_='finance-statements-year')

                q_data = extract_financial_table(q_container)
                y_data = extract_financial_table(y_container)

                if y_data:
                    save_to_db(connection, stock_code, y_data, '0') # 연간
                if q_data:
                    save_to_db(connection, stock_code, q_data, '1') # 분기
                
                connection.commit()
                time.sleep(0.5)
                
            except Exception as e:
                print(f"⚠️ Error processing {stock_code}: {e}")
                
    finally:
        connection.close()
    
    print("✨ Update complete!")

if __name__ == "__main__":
    main()

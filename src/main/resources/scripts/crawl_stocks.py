import requests
import pandas as pd
from bs4 import BeautifulSoup
from tqdm import tqdm
import os

def get_stock_list(market_code):
    """
    네이버 금융에서 시장별(KOSPI: 0, KOSDAQ: 1) 종목 목록을 가져오는 함수
    """
    stocks = []
    base_url = "https://finance.naver.com/sise/sise_market_sum.naver"
    params = {'sosok': market_code, 'page': 1}

    # 첫 페이지 요청
    response = requests.get(base_url, params=params)
    response.raise_for_status()
    soup = BeautifulSoup(response.text, "lxml")

    # 마지막 페이지 번호 추출
    last_page_tag = soup.select_one("td.pgRR a")
    if last_page_tag:
        last_page = int(last_page_tag['href'].split('page=')[-1])
    else:
        last_page = 1

    market_name = "KOSPI" if market_code == 0 else "KOSDAQ"

    for page in tqdm(range(1, last_page + 1), desc=f"Scraping {market_name} stocks"):
        params['page'] = page
        response = requests.get(base_url, params=params)
        response.raise_for_status()
        soup = BeautifulSoup(response.text, "lxml")

        # 테이블 찾기
        table = soup.find("table", {"class": "type_2"})
        if not table:
            continue

        tbody = table.find("tbody")
        if not tbody:
            continue

        rows = tbody.find_all("tr")
        for row in rows:
            cols = row.find_all("td")
            if len(cols) < 2:
                continue
            a_tag = cols[1].find("a")  # 종목명 컬럼
            if a_tag and 'href' in a_tag.attrs:
                href = a_tag['href']
                stock_code = href.split("code=")[-1]
                stock_name = a_tag.text.strip()
                stock_name = stock_name.replace("'", "''")  # SQL 이스케이프
                stocks.append({'code': stock_code, 'name': stock_name, 'market_name': market_name})

    return stocks

def create_sql_file(all_stocks):
    """
    수집된 종목 목록으로 SQL INSERT 문을 생성하여 파일에 저장
    STOCK 테이블에 없는 종목만 추가 (INSERT IGNORE)
    """
    filename = "stocks.sql"
    with open(filename, "w", encoding="utf-8") as f:
        f.write("-- 네이버 증권 상장 기업 목록 SQL 스크립트\n")
        
        # INSERT IGNORE를 사용하여 중복 키 발생 시 무시 (MySQL 문법)
        f.write("INSERT IGNORE INTO stock (stock_code, stock_name, market_name) VALUES\n")
        insert_statements = []
        for stock in all_stocks:
            insert_statements.append(f"('{stock['code']}', '{stock['name']}', '{stock['market_name']}' )")
        f.write(",\n".join(insert_statements))
        f.write(";\n")

    print(f"\nSQL 파일 '{filename}' 생성 완료!")
    print(f"총 {len(all_stocks)}개의 종목 정보 저장됨.")

if __name__ == "__main__":
    print("네이버 금융에서 KOSPI 종목 수집 중...")
    kospi_stocks = get_stock_list(0)
    print("네이버 금융에서 KOSDAQ 종목 수집 중...")
    kosdaq_stocks = get_stock_list(1)

    all_stocks = kospi_stocks + kosdaq_stocks

    # 중복 제거 (코드 기준)
    unique_stocks = list({s['code']: s for s in all_stocks}.values())

    create_sql_file(unique_stocks)

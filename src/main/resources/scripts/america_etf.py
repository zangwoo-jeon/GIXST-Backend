import requests
import time
import os
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

N_ETF_URL = os.environ.get('N_ETF_URL')


def scrape_n_etfs_to_sql(output_file="n_etf_list.sql"):
    base_url = N_ETF_URL
    all_sql_statements = []
    page = 1

    # 시장 코드 매핑 딕셔너리
    market_map = {
        "AMX": "AMS",
        "NSQ": "NAS",
        "NYS": "NYS"
    }

    while True:
        params = {
            "sortTypeCode": "tradingValue",
            "pageSize": 50,
            "page": page
        }

        print(f"페이지 {page} 데이터 수집 중...")
        try:
            response = requests.get(base_url, params=params)
            if response.status_code != 200:
                break

            data = response.json()
            items = data.get("result", {}).get("result", [])

            if not items:
                break

            for item in items:
                # 수정된 부분: stock_code 자리에 'name' 값을 할당
                ticker = item.get("name")  # 예: SOXL
                full_name = item.get("subName").replace("'", "''")  # SQL 따옴표 처리

                raw_market = item.get("stockExchangeType", {}).get("code")
                market = market_map.get(raw_market, raw_market)

                # SQL INSERT 문 생성
                sql = (
                    f"INSERT INTO stock (stock_code, stock_name, market_name, stock_type, is_suspended, is_common)\n"
                    f"VALUES ('{ticker}', '{full_name}', '{market}', 'US_ETF', 0, 0);"
                )
                all_sql_statements.append(sql)

            # 다음 페이지가 있는지 확인
            if not data.get("result", {}).get("hasNext", False):
                break

            page += 1
            time.sleep(0.3)  # 서버 부하 방지

        except Exception as e:
            print(f"오류 발생: {e}")
            break

    # 파일로 저장
    with open(output_file, "w", encoding="utf-8") as f:
        f.write("\n".join(all_sql_statements))

    print(f"\n성공! 총 {len(all_sql_statements)}개의 실행문이 '{output_file}'에 저장되었습니다.")


# 실행
scrape_n_etfs_to_sql()
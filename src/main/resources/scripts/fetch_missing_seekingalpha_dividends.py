import pymysql
import requests
import json
import time
from tqdm import tqdm

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

def to_seeking_alpha_ticker(ticker):
    """
    내부 티커 형식(예: BRK/A)을 Seeking Alpha 형식(예: BRK.A)으로 변환합니다.
    """
    return ticker.replace("/", ".").lower()

def get_missing_stocks(start_id=None, end_id=None):
    """
    배당 정보가 없는 해외 종목들만 조회합니다. (범위 지정 가능)
    """
    try:
        connection = pymysql.connect(**DB_CONFIG)
        with connection.cursor() as cursor:
            sql = """
            SELECT s.stock_id, s.stock_code
            FROM stock s
            LEFT JOIN stock_dividend sd ON s.stock_id = sd.stock_id
            WHERE sd.stock_id IS NULL 
              AND s.stock_type = 'US_STOCK' 
              AND (s.is_suspended IS NULL OR s.is_suspended = 0)
            """
            params = []
            if start_id is not None and end_id is not None:
                sql += " AND s.stock_id BETWEEN %s AND %s"
                params = [start_id, end_id]
            
            sql += " ORDER BY s.stock_id ASC"
            
            cursor.execute(sql, params)
            return cursor.fetchall()
    except Exception as e:
        print(f"❌ DB connection failed: {e}")
        return []
    finally:
        if 'connection' in locals():
            connection.close()

def fetch_and_save_missing_dividends(start_id=None, end_id=None):
    stocks = get_missing_stocks(start_id, end_id)
    if not stocks:
        print("✅ No US stocks with missing dividend data found.")
        return

    print(f"🚀 Found {len(stocks)} US stocks with missing dividend data. Starting sync...")

    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cookie": """machine_cookie=fhschazay61769689021; _pxvid=6a7196ea-fd0c-11f0-a946-658b605e3082; _gcl_au=1.1.612454606.1769689035; _fbp=fb.1.1769689035263.838331234815353629; _ga=GA1.1.1559190872.1769689035; sa-user-id=s%253A0-a920bce5-e829-5e1a-587d-cfebe5e29a23.WAVKYkukzR8QGDCPOPpE%252Bpc3OhSd8iZO0b4ctbmIbLI; sa-user-id-v2=s%253AqSC85egpXhpYfc_r5eKaI9Mi2YA.AopLe9ULZ99kHdSZORt6hfsqDpfJwCY2mtco92rS5Is; hubspotutk=f139ec8883fd548e2862a89c6f070d15; _hjSessionUser_65666=eyJpZCI6ImU5YzM3ZjI1LTNmYmUtNTMzZS1hZmYyLTIxMGNmOGExZDJmYyIsImNyZWF0ZWQiOjE3Njk2ODkwMzUyMzgsImV4aXN0aW5nIjp0cnVlfQ==; session_id=b763ff8e-72f0-47e7-95ab-350b0f88fb03; pxcts=fa287402-fd7a-11f0-b02d-6279f7477329; _hjSession_65666=eyJpZCI6IjIzZWU0NjE2LTNjMTktNDk3Yi1hNzdiLTU5MTgwODY1NTY3MiIsImMiOjE3Njk3MzY1MDg3MzAsInMiOjAsInIiOjAsInNiIjowLCJzciI6MCwic2UiOjAsImZzIjowLCJzcCI6MH0=; _hjHasCachedUserAttributes=true; dicbo_id=%7B%22dicbo_fetch%22%3A1769736509308%7D; __hstc=234155329.f139ec8883fd548e2862a89c6f070d15.1769689035982.1769707155191.1769736509378.5; __hssrc=1; __hssc=234155329.2.1769736509378; sailthru_pageviews=2; g_state={"i_l":2,"i_ll":1769736517683,"i_b":"iUHwfR4QNMZE1Pcp5XtmtI1cngjAc2+sBOjm1kuzH38","i_e":{"enable_itp_optimization":3},"i_p":1769795125880}; sa-user-id-v3=s%253AAQAKIJHG19J-7xYDpuBnKzQIZmUlNNvas6A9Q2LsymEAbLqXEAEYAyDGkvDLBjABOgSGrMY-QgQWB5iE.v8cpJzGNMA3ga2LJKB5Ge7kAjsZq8ioz14lDAlwLZg0; sailthru_visitor=6f6601d8-dd7a-42c2-b7c4-5e7abe6ddf42; _uetsid=729c91e0fd0c11f0ade455449cadc410|1009i3|2|g35|0|2220; _sasource=; userLocalData_mone_session_lastSession=%7B%22machineCookie%22%3A%22fhschazay61769689021%22%2C%22machineCookieSessionId%22%3A%22fhschazay61769689021%261769736506466%22%2C%22sessionStart%22%3A1769736506466%2C%22sessionEnd%22%3A1769738325119%2C%22isSessionStart%22%3Afalse%2C%22lastEvent%22%3A%7B%22event_type%22%3A%22mousemove%22%2C%22timestamp%22%3A1769736525119%7D%2C%22firstSessionPageKey%22%3A%221a1284b3-2386-41e1-9e2c-74aeb8126343%22%7D; _uetvid=729ceff0fd0c11f0a3e9815265a8c54a|1f38tuz|1769736528231|5|1|bat.bing.com/p/insights/c/k; sailthru_content=fa2025262fcef948baf7b61d510991e3c88d6e716051f8ef7eeb8679ff6bd2d01f29602a46303c0235065c7e1cf21493b8736ac62b29c44de1480b947c4b918de5c720e9232427841fd0a2ab7b2f6c4797a7b49a56ef5a3896c1853e933099a388ba5c765baa4c597ee160172b80bb9344bd3d87ce63865933d414b9cf46db238f82e7856b6d9a3005b4a9cc2c6cde7627ce43fd3ce7aa141bde4261c9af238aacb407b993e924c1b53fba2d89630bb5f3247769a8d970469a24dedb2581b39358d17db33b9e1696f9e322ee01b845f550d1ea7aed4473f0c48c1c3f2432be71b52624f3aa6be2d685985686dd2b458028f25b50c4194a961609070316c5e7ae; _px3=d53b891963cacb3ac12f5da53d6fa03e60ba87b2832e62ba74d6992664e7bf1c:aQRiclZCkgU/pOICwu2o6MCrP4U3jcNKUmAqmYzUYgbDTpDvq8qcy3yGAo0A3G7OAi+M6U67hMWIzhtZd0Mdkg==:1000:KVsc5IHEY6bY7wDxoAZQCZBK1I0qBkXKwQtoGydw9peeaFTp+3C9/fRSzJEkBfZunQqy4Ql7r7lfRJxAW7lo2oI6veHPttJIp3ChIIJ1buDmaXUeLVwQDkNSjtSSnFcnGzmhh8cNRfGs8m6hrJ84de4q3o1NT0DSfVYZ+5oDfDJuNchO9s+lvgfc3d5b3FT3GmV/7ENRrw8MvevxcZN7zoyjVL+hvrinCZ3Sw1kkYXE6VQt+SD4SHB4B50zaoUlyCHvR+iswrDSXYcBmZuaH4CtzxHGZWniOPmS4QrqbTZp8XLIW86YMowwZfEEXAQy5Fe69vuPVIQAxY6a5b05wwOZ/izhAtVctDrhvXaYv2qasw2TBIonDYMeiXCd6Dde6XMUbrxcXmX9jU0OJ8mfcbfTg+Q1QPzgxfhlKhJkqMpPMXoKuY89sVKEb+Dk5j4P5wAZqODNCnsUWOfTpwP1xaNcnP9jIadi8Pj8dm9gWYAkV8npV0//CBb02fwrhyCXxHitvHV5Tdiyz41w3/W089A==; _pxde=2b4e14d7f0f75a4269e1699d1543704511675e0769bc67a57b4b2d7d302ef1af:eyJ0aW1lc3RhbXAiOjE3Njk3MzY1Mjg5NjYsImZfa2IiOjB9; _ga_KGRFF2R2C5=GS2.1.s1769736508$o7$g1$t1769736528$j40$l0$h0; LAST_VISITED_PAGE=%7B%22pathname%22%3A%22https%3A%2F%2Fseekingalpha.com%2Fsymbol%2FNVDA%2Fdividends%2Fhistory%22%7D"""
    }

    try:
        connection = pymysql.connect(**DB_CONFIG)
        with connection.cursor() as cursor:
            total_count = 0
            for stock_entry in tqdm(stocks, desc="Fetching Missing Dividends"):
                stock_id = stock_entry['stock_id']
                ticker = stock_entry['stock_code']
                sa_ticker = to_seeking_alpha_ticker(ticker)
                
                # 티커별 동적 Referer 설정
                headers["Referer"] = f"https://seekingalpha.com/symbol/{ticker.upper()}/dividends/history"
                
                url = f"https://seekingalpha.com/api/v3/symbols/{sa_ticker}/dividend_history?years=5"
                try:
                    response = requests.get(url, headers=headers)
                    if response.status_code == 404:
                        print(f"  - [{ticker}] Skip: Ticker not found (404)")
                        continue
                    if response.status_code == 403:
                        print(f"  - [{ticker}] CRITICAL: Access Blocked (403). Cookie might be expired.")
                        break # 403 발생 시 세션이 막힌 것이므로 중단하는 것이 좋음
                    
                    response.raise_for_status()
                    json_data = response.json()
                    items = json_data.get("data", [])

                    if not items:
                        print(f"  - [{ticker}] Skip: No dividend history found")
                        continue

                    ticker_count = 0
                    for entry in items:
                        attr = entry.get("attributes", {})
                        raw_record_date = attr.get("record_date")
                        raw_pay_date = attr.get("pay_date")
                        amount = attr.get("amount")

                        if raw_record_date and raw_pay_date and amount:
                            clean_record_date = raw_record_date.replace("-", "")
                            formatted_pay_date = raw_pay_date.replace("-", "/")

                            insert_sql = """
                            INSERT INTO stock_dividend (stock_id, record_date, payment_date, dividend_amount, dividend_rate, stock_price)
                            VALUES (%s, %s, %s, %s, 0.0, 0.0)
                            """
                            cursor.execute(insert_sql, (stock_id, clean_record_date, formatted_pay_date, amount))
                            ticker_count += 1
                            total_count += 1

                    connection.commit()
                    # print(f"  - [{ticker}] Success: {ticker_count} records saved")

                except Exception as e:
                    print(f"  - [{ticker}] Error: {str(e)}")
                    continue
                finally:
                    time.sleep(3) # 성공/실패 여부와 상관없이 무조건 3초 대기 ( Always-Sleep )

        print(f"\n✨ Task complete! Total {total_count} missing dividend records saved.")

    except Exception as e:
        print(f"❌ Error occurred: {e}")
    finally:
        if 'connection' in locals():
            connection.close()

if __name__ == "__main__":
    try:
        s_id = input("시작 stock_id (전체는 엔터): ").strip()
        e_id = input("종료 stock_id (전체는 엔터): ").strip()
        
        start_id = int(s_id) if s_id else None
        end_id = int(e_id) if e_id else None
        
        fetch_and_save_missing_dividends(start_id, end_id)
    except ValueError:
        print("❌ 올바른 숫자를 입력해 주세요.")

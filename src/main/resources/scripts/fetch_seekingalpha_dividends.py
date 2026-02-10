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
    내부 티커 형식(예: BRK.A)을 Seeking Alpha 형식(예: brk.a)으로 변환합니다.
    """
    return ticker.lower()

def get_target_stocks():
    """
    DB에서 모든 해외 주식 티커를 가져옵니다.
    """
    try:
        connection = pymysql.connect(**DB_CONFIG)
        with connection.cursor() as cursor:
            sql = "SELECT stock_id, stock_code FROM stock WHERE stock_type IN ('US_STOCK', 'US_ETF') AND (is_suspended IS NULL OR is_suspended = 0)"
            cursor.execute(sql)
            return cursor.fetchall()
    except Exception as e:
        print(f"❌ DB connection failed: {e}")
        return []
    finally:
        if 'connection' in locals():
            connection.close()

def fetch_and_save_dividends():
    stocks = get_target_stocks()
    if not stocks:
        print("❌ No active US stocks found.")
        return

    print(f"🚀 Found {len(stocks)} US stocks to process.")

    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cookie": """machine_cookie=f52fr60k8d1769681573; _pxvid=1436612a-fcfb-11f0-b2ef-340a01f3a53b; pxcts=143668b0-fcfb-11f0-b2ef-d7d8ff7ee782; _gcl_au=1.1.1206771803.1769681577; _ga=GA1.1.1141012068.1769681577; sa-user-id=s%253A0-0c343255-efcd-5504-5c8c-d98825f930be.qNBHP3UgToYWOUzCVVMgJHfrTPByrAq0Xjr78WMiIEs; sa-user-id-v2=s%253ADDQyVe_NVQRcjNmIJfkwvtMi2YA.TiJQwlFGXoa3%252BC8SAl%252FmTVWmJtw5e5a6uLnwJ%252BjLEmE; sa-user-id-v3=s%253AAQAKIJtM5UThZZfwZwt2XmKBojxBis_iZ3F8w4IOeQddAMvUEAEYAyDHyv_JBjABOgRi0YmEQgSpdb2y.OLommZAKTV8qDFZjYTbsb6OTBO1o4pn866n4uxBPud8; hubspotutk=fd1d54a28eb68ce5cb928934938b95e9; __hssrc=1; g_state={"i_l":0,"i_ll":1769681580259,"i_b":"B5hLcxojUXvSt89qI29BHqPNla+kRCosYk+4I8V7N7E","i_e":{"enable_itp_optimization":3}}; _sasource=; __stripe_mid=11f43df8-5828-4454-841b-bec8471eae000c674d; user_id=64066946; user_nick=; user_devices=; u_voc=; sapu=101; user_remember_token=af5187dc3fc529b89747ce311301e662cd6e8a14; _sapi_session_id=hBnMRbmNZ3nkwD5k9%2B023pgWB5AzXE6BUj2PPewQb%2FTfzzazL%2BL0qjx44%2FbMBLL53zoEwRr5CB76pm7LYD9YTivgu%2FGN7E8oCTvSzSV4vYYsqIDh%2BIV5I4XJxEYmqRxiZQEA0yoJZm7%2Fn0hprNj%2BnpmIsf4sqDFOBAcKHvALubArDshU8WRQE1WHzQjtUkyXpPLW9VSiIbYHKb0Piiz5QJiHv3aDh4tU7nGgo4xx7QcxKWGZwp7qmXxfHiAZV%2FNiBTmJAlRTkzfEKY15gWsTyA9ueUI%2B3EpkK5tcXt1GQUZo69cZO5tYU%2BUZibvIwNZAfMd9AuFYRvQonc4U8nhMv2nuFBHDbJgaRMrtGHZN9zb5D53TT6dkyMaBSipPZub20zdwjgYr6Ob6R89sJBKpNL0MoILdgNOjUw5Ea%2F7b%2BwB%2F1gfLhX1ZO6SGeHtgHQjGBIDI%2F%2FpezdGVVKh0--giav4%2FJoZnLDb%2F7H--C0mihtcqNsSLKfIF2hV95A%3D%3D; sailthru_hid=79c66568aee9e10f03070aaad73262a5697b390fa49262217209328c078da3c03cf79bbfc0f65e0eaa220046; _hjSessionUser_65666=eyJpZCI6ImRhM2MwZWNiLThlZmYtNTljYi04NTU3LTM1NThhNWNiOWNmYiIsImNyZWF0ZWQiOjE3Njk2ODE1NzcwNzksImV4aXN0aW5nIjp0cnVlfQ==; _hjHasCachedUserAttributes=true; gk_user_access=1**1769685176; gk_user_access_sign=d8943c27a890e476355843c902d32d5f5b6af402; user_cookie_key=1bnxmv5; session_id=a8a0e896-3be2-440b-953f-42c8d18c569e; _hjSession_65666=eyJpZCI6Ijk1ZTQ5M2Q5LWQ2NGUtNDU2YS1iNjljLTVhNTAwYTIwZDM4OSIsImMiOjE3Njk2ODg5MDUzMDgsInMiOjAsInIiOjAsInNiIjowLCJzciI6MCwic2UiOjAsImZzIjowLCJzcCI6MX0=; sa-r-source=gemini.google.com; dicbo_id=%7B%22dicbo_fetch%22%3A1769688905394%7D; __hstc=234155329.fd1d54a28eb68ce5cb928934938b95e9.1769681580249.1769683403385.1769688906844.3; userLocalData_mone_session_lastSession=%7B%22machineCookie%22%3A%22f52fr60k8d1769681573%22%2C%22machineCookieSessionId%22%3A%22f52fr60k8d1769681573%261769688901162%22%2C%22sessionStart%22%3A1769688901162%2C%22sessionEnd%22%3A1769690721552%2C%22isSessionStart%22%3Afalse%2C%22lastEvent%22%3A%7B%22event_type%22%3A%22mousemove%22%2C%22timestamp%22%3A1769688921552%7D%2C%22firstSessionPageKey%22%3A%223bc85061-efde-4d92-b789-29afaea29185%22%7D; sa-r-date=2026-01-29T12:15:25.784Z; sailthru_pageviews=3; _ga_KGRFF2R2C5=GS2.1.s1769688905$o2$g1$t1769689016$j57$l0$h0; _uetsid=152f68a0fcfb11f0a4602bb60b2e3252|1fi8cv|2|g34|0|2220; _uetvid=152fa180fcfb11f09863e3e59ac68185|awnrh4|1769689017098|3|1|bat.bing.com/p/insights/c/k; sailthru_content=c276a9ee4cfb75ff7489fc42ae55cc0d44148b2173322a96626363e59d63ec9cfa75fa9f89492f447d8edf306cc8c7fdccd86c8a3aeee8e3f1c188b1166a2e9f770429ea84ff0c5d330bee92090f23e52ee3ed6b6bfce9a1ad6f17e8d14c83987ef53abe916ba5de3fe1997df57c1ab711fa3dc693e0e5a64ded0c33a4ad3b952f1008d87fc6c2ad903c59cf00872140a3e18216d5ccbb3f7981fc998a5a32a433bab4b484036ba6982c9a170369d517543d43b0884e7a5393f99889921bed6b7ac619eb765b4fadb13254ac6a96d743d93f1d0fe9c0357fe8dccfd3c755aa3558d17db33b9e1696f9e322ee01b845f5c36180dae684c4666061495eb43e33b2; sailthru_visitor=89be9e25-b70e-42c0-9263-f120dfa330cf; __hssc=234155329.3.1769688906844; _px3=7046a1f396f911200cf1e83600e2b544223a26263e90123cfabc0daf7d4eca66:HqXfg7q42z0sf008NGayEDv+UgMBx4SakNDNLQ62SRzBNZuIiNxOXQzRFiLcTNshozUQ8A1dsujWPxyCyIlnmQ==:1000:u772ndIJJDC8SV9FALolwFczFwDRlJHio9R8gMAE1KdrFQ2tbbp7v6HpPcyviddFB/eXIbkNiT1So6bNUXcp+NF+NhVkEegQNruWgvRvzfub3gT1QtWcGRPiAi2z5PbXAJCqhCInCMKvJJozWy/Frz67blXDMSiATt3ChfRww7H4AS2xPPsl03c7sp5wbwAnZedMYfW3PBmmjkznPH19onlv6s3yWxqnVdASMh/yTMtmNLekCxfA1VycnVWNBKndDlg0qq/7liznr9yhD84vWbkkeRGqORcNYhi/9Ceg/3FHn3ARlfbjkWTwA4Yf1H5siJ9N0to+biN1shKbKSXrIjwMsEXr+OXl2BihBngmCi2DZuil6Lhvg3lJ0PPbStIdKVdddfzAl39bqN34oQ+htm80EUc9ZBt4xsy956zqI4dwXFJJ2Uymd8xfVFb27YGFPD1KXQnexnFSo48m0GKbJK3ly7V9IY5LLQ2pMUC9RitDAur/3JhvQcCJ/9PhKGFUEDZ3eDB9jNya0dfn1crcTA==; _pxde=ae45338a6750e55f5ea7f9ce31f3d01c51d20f099bd087632eaf9c5708c659ef:eyJ0aW1lc3RhbXAiOjE3Njk2ODkwNzY5MDgsImZfa2IiOjB9; LAST_VISITED_PAGE=%7B%22pathname%22%3A%22https%3A%2F%2Fseekingalpha.com%2Fsymbol%2FBAC%2Fdividends%2Fhistory%22%2C%22fromMpArticle%22%3Afalse%7D"""
    }

    try:
        connection = pymysql.connect(**DB_CONFIG)
        with connection.cursor() as cursor:
            total_count = 0
            for stock_entry in tqdm(stocks, desc="Fetching Dividends"):
                stock_id = stock_entry['stock_id']
                ticker = stock_entry['stock_code']
                sa_ticker = to_seeking_alpha_ticker(ticker)
                
                # 티커별 동적 Referer 설정
                headers["Referer"] = f"https://seekingalpha.com/symbol/{ticker.upper()}/dividends/history"
                
                url = f"https://seekingalpha.com/api/v3/symbols/{sa_ticker}/dividend_history?years=5"
                try:
                    response = requests.get(url, headers=headers)
                    if response.status_code == 404:
                        continue # Skip if not found
                    
                    response.raise_for_status()
                    json_data = response.json()
                    items = json_data.get("data", [])

                    if not items:
                        continue

                    for entry in items:
                        attr = entry.get("attributes", {})
                        raw_record_date = attr.get("record_date") # yyyy-MM-dd
                        raw_pay_date = attr.get("pay_date")       # yyyy-MM-dd
                        amount = attr.get("amount")

                        if raw_record_date and raw_pay_date and amount:
                            # 포맷 변환 (yyyy-MM-dd -> yyyyMMdd / yyyy/MM/dd)
                            clean_record_date = raw_record_date.replace("-", "")
                            formatted_pay_date = raw_pay_date.replace("-", "/")

                            insert_sql = """
                            INSERT INTO stock_dividend (stock_id, record_date, payment_date, dividend_amount, dividend_rate, stock_price)
                            VALUES (%s, %s, %s, %s, 0.0, 0.0)
                            """
                            cursor.execute(insert_sql, (stock_id, clean_record_date, formatted_pay_date, amount))
                            total_count += 1

                    connection.commit()
                    time.sleep(1) # Rate limit 방지

                except Exception as e:
                    # print(f"⚠️ Error for {ticker}: {e}")
                    continue

        print(f"\n✨ Task complete! Total {total_count} dividend records saved.")

    except Exception as e:
        print(f"❌ Error occurred: {e}")
    finally:
        if 'connection' in locals():
            connection.close()

if __name__ == "__main__":
    fetch_and_save_dividends()

import csv
import mysql.connector
import os
from datetime import datetime
import decimal

# Database connection configuration
DB_CONFIG = {
    "host": "100.77.73.46",
    "user": "user",
    "password": "0717",
    "database": "aisa_portfolio"
}

CSV_PATH = os.path.join(os.path.dirname(__file__), "data", "etf_list.csv")

def migrate():
    try:
        conn = mysql.connector.connect(**DB_CONFIG)
        cursor = conn.cursor(dictionary=True)
        print("Connected to database.")

        # Check stock_type column definition
        cursor.execute("SHOW COLUMNS FROM stock LIKE 'stock_type'")
        col_info = cursor.fetchone()
        print(f"stock_type column info: {col_info}")

        if col_info and 'enum' in col_info['Type'].lower():
            print("stock_type is an ENUM. Altering to VARCHAR(255) to support new types.")
            cursor.execute("ALTER TABLE stock MODIFY stock_type VARCHAR(255)")
            print("Altered stock_type to VARCHAR(255).")

        # Ensure etf_detail table exists (in case Hibernate hasn't created it yet)
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS etf_detail (
                stock_id BIGINT PRIMARY KEY,
                underlying_index VARCHAR(255),
                index_provider VARCHAR(255),
                tracking_multiplier DOUBLE,
                replication_method VARCHAR(255),
                manager VARCHAR(255),
                total_expense DECIMAL(10, 6),
                tax_type VARCHAR(255),
                listing_date DATE,
                FOREIGN KEY (stock_id) REFERENCES stock(stock_id)
            )
        """)
        print("Ensured etf_detail table exists.")

        # Read CSV
        encodings = ['utf-8', 'cp949', 'euc-kr']
        reader = None
        f = None
        for enc in encodings:
            try:
                # Try reading with specific encoding
                test_f = open(CSV_PATH, mode='r', encoding=enc)
                sample = test_f.read(4096)
                test_f.close()
                
                f = open(CSV_PATH, mode='r', encoding=enc)
                # Determine dialect (comma or tab)
                if '\t' in sample:
                    reader = csv.DictReader(f, delimiter='\t')
                else:
                    reader = csv.DictReader(f)
                
                # Check if we can read the header correctly
                header = reader.fieldnames
                if header and ('단축코드' in header or '표준코드' in header):
                    print(f"Successfully read CSV with encoding: {enc}")
                    break
                else:
                    f.close()
                    reader = None
            except Exception as e:
                if f: f.close()
                continue
        
        if not reader:
            print("Could not read CSV file or headers mismatch.")
            return

        success_count = 0
        skip_count = 0

        for row in reader:
            stock_code = row.get('단축코드', '').strip()
            if not stock_code:
                continue

            # Find stock entry
            cursor.execute("SELECT stock_id, stock_name FROM stock WHERE stock_code = %s", (stock_code,))
            stock = cursor.fetchone()

            if not stock:
                skip_count += 1
                continue

            stock_id = stock['stock_id']
            stock_name = stock['stock_name']

            # Determine StockType
            foreign_keywords = ["미국", "나스닥", "NASDAQ", "S&P500", "SNP500", "해외", "글로벌", "재팬", "유로", "중국", "차이나", "인도", "베트남", "독일"]
            is_foreign = any(kw in stock_name for kw in foreign_keywords)
            stock_type = 'FOREIGN_ETF' if is_foreign else 'DOMESTIC_ETF'

            # Update stock table
            cursor.execute("UPDATE stock SET stock_type = %s, is_common = 0 WHERE stock_id = %s", (stock_type, stock_id))

            # Prepare EtfDetail data
            underlying_index = row.get('기초지수명', '')
            index_provider = row.get('지수산출기관', '')
            
            try:
                tracking_multiplier = float(row.get('추적배수', '1'))
            except:
                tracking_multiplier = 1.0

            replication_method = row.get('복제방법', '')
            manager = row.get('운용사', '')
            
            total_expense_raw = row.get('총보수', '0').replace('%', '').strip()
            try:
                total_expense = decimal.Decimal(total_expense_raw)
            except:
                total_expense = decimal.Decimal('0')

            tax_type = row.get('과세유형', '')
            
            listing_date_raw = row.get('상장일', '').replace('-', '').replace('/', '').strip()
            try:
                if len(listing_date_raw) == 8:
                    listing_date = datetime.strptime(listing_date_raw, '%Y%m%d').date()
                else:
                    listing_date = None
            except:
                listing_date = None

            # Upsert EtfDetail
            cursor.execute("""
                INSERT INTO etf_detail (stock_id, underlying_index, index_provider, tracking_multiplier, 
                                       replication_method, manager, total_expense, tax_type, listing_date)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON DUPLICATE KEY UPDATE
                    underlying_index = VALUES(underlying_index),
                    index_provider = VALUES(index_provider),
                    tracking_multiplier = VALUES(tracking_multiplier),
                    replication_method = VALUES(replication_method),
                    manager = VALUES(manager),
                    total_expense = VALUES(total_expense),
                    tax_type = VALUES(tax_type),
                    listing_date = VALUES(listing_date)
            """, (stock_id, underlying_index, index_provider, tracking_multiplier, replication_method, manager, total_expense, tax_type, listing_date))

            success_count += 1

        conn.commit()
        print(f"Migration completed. Successfully updated {success_count} ETFs. Skipped {skip_count} unknown codes.")

    except Exception as e:
        print(f"An error occurred: {e}")
    finally:
        if 'conn' in locals() and conn.is_connected():
            cursor.close()
            conn.close()

if __name__ == "__main__":
    migrate()

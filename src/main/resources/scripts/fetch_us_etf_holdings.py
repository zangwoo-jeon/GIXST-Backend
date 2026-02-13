import sys
import json
import yfinance as yf
import pandas as pd

def fetch_top_holdings(ticker_symbol):
    try:
        etf = yf.Ticker(ticker_symbol)
        # funds_data.top_holdings returns a DataFrame with index as Symbol
        # Columns: Name, Holding Percent, etc.
        holdings_df = etf.funds_data.top_holdings
        
        if holdings_df is None or holdings_df.empty:
            print(json.dumps([]))
            return

        # Limit to top 10
        top_10 = holdings_df.head(10)
        
        result = []
        for symbol, row in top_10.iterrows():
            # Normalize ticker: Replace '-' with '.' (e.g., BRK-B -> BRK.B)
            normalized_symbol = str(symbol).replace('-', '.')
            
            # yfinance returns percent as decimal (e.g., 0.078 for 7.8%) or whole number?
            # User example: 0.078345 -> This is likely decimal representation of 7.83%
            # Our DB expects similar or we convert. Typically we store weight.
            # Let's check user example output again if needed, but standardizing on what we get.
            
            holding_data = {
                "ticker": normalized_symbol,
                "name": row['Name'],
                "weight": row['Holding Percent'] # Keeping raw value, Java side can handle conversion if needed
            }
            result.append(holding_data)

        print(json.dumps(result))

    except Exception as e:
        # In case of error, print empty list or error object. 
        # Printing empty list to avoid breaking Java side which expects JSON array.
        # Log error to stderr for debugging
        sys.stderr.write(f"Error fetching holdings for {ticker_symbol}: {str(e)}\n")
        print(json.dumps([]))

if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.stderr.write("Usage: python fetch_us_etf_holdings.py <TICKER>\n")
        sys.exit(1)
        
    ticker = sys.argv[1]
    fetch_top_holdings(ticker)

"""Fetch reproducible WDI snapshots. Run before packaging, then review/commit the result.
No synthetic values, mixed-year ranks, or regional aggregate entities are included.
"""
import argparse
import datetime
import json
import pathlib
import time
import urllib.request

BASE = 'https://api.worldbank.org/v2'
INDICATORS = {'gdp': 'NY.GDP.MKTP.CD', 'ppp': 'NY.GDP.MKTP.PP.CD', 'exports': 'NE.EXP.GNFS.CD'}

def fetch(path):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(BASE + path, timeout=90) as response:
                result = json.load(response)
            if not isinstance(result, list) or len(result) != 2 or not isinstance(result[1], list):
                raise ValueError('Invalid World Bank response')
            if int(result[0]['pages']) != 1:
                raise ValueError('Response exceeds page size; increase per_page or implement paging')
            return result[1]
        except Exception:
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--from-year', type=int, default=2022)
    parser.add_argument('--to-year', type=int, default=2024)
    args = parser.parse_args()
    if not 1960 <= args.from_year <= args.to_year <= datetime.date.today().year:
        parser.error('Use an ordered year range between 1960 and the current year')
    countries = {c['id']: c for c in fetch('/country?format=json&per_page=400') if c['region']['id'] != 'NA'}
    rows = []
    retrieved = datetime.datetime.now(datetime.timezone.utc).isoformat()
    for metric, code in INDICATORS.items():
        for row in fetch(f'/country/all/indicator/{code}?date={args.from_year}:{args.to_year}&format=json&per_page=20000'):
            country = countries.get(row['countryiso3code'])
            if country and row['value'] is not None and row['value'] >= 0:
                rows.append([country['id'], country['name'], country['region']['value'], metric, row['date'], row['value'], retrieved])
    if len(rows) < 100:
        raise ValueError('Incomplete dataset; refusing to replace snapshot')
    target = pathlib.Path(__file__).resolve().parents[1] / 'src/main/resources/data/world-bank.csv'
    target.parent.mkdir(parents=True, exist_ok=True)
    # JSON Lines avoids dependency on a CSV parser in Java while preserving names safely.
    target = target.with_suffix('.jsonl')
    temp = target.with_suffix('.tmp')
    with temp.open('w', encoding='utf-8', newline='\n') as output:
        for code, name, region, metric, year, value, retrieved_at in sorted(rows):
            output.write(json.dumps(dict(country=code, name=name, region=region, indicator=metric,
                year=int(year), value=value, retrievedAt=retrieved_at), ensure_ascii=False) + '\n')
    temp.replace(target)
    print(f'Wrote {len(rows)} genuine WDI observations for {len(countries)} economies to {target.name}')

if __name__ == '__main__':
    main()

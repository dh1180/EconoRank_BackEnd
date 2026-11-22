"""Download the newest official IMF WEO country workbook and export a bootstrap snapshot.
Runtime refresh uses the same official source in Java; no scraping of third-party rankings.
"""
import datetime, decimal, hashlib, io, json, pathlib, re, urllib.parse, urllib.request, zipfile
import xml.etree.ElementTree as ET

PAGE = 'https://data.imf.org/en/datasets/IMF.RES:WEO'
NS = {'s':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
UA = 'StatAgora/0.1 (+https://github.com/dh1180/StatAgora_BackEnd)'

def download(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent':UA}), timeout=90) as response:
        return response.read()

def column(address):
    value = 0
    for c in re.match('[A-Z]+',address)[0]: value=value*26+ord(c)-64
    return value-1

def parse(blob,url):
    z=zipfile.ZipFile(io.BytesIO(blob))
    strings=[''.join(t.itertext()) for t in ET.fromstring(z.read('xl/sharedStrings.xml'))]
    workbook=ET.fromstring(z.read('xl/workbook.xml'))
    relationship=next(s.attrib['{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id'] for s in workbook.find('s:sheets',NS) if s.attrib['name']=='Countries')
    rels=ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))
    target=next(r.attrib['Target'] for r in rels if r.attrib['Id']==relationship)
    target=target.lstrip('/') if target.startswith('/') else 'xl/'+target
    rows=ET.fromstring(z.read(target)).find('s:sheetData',NS)
    def row_values(row):
        result={}
        for cell in row:
            v=cell.find('s:v',NS)
            if v is not None: result[column(cell.attrib['r'])]=strings[int(v.text)] if cell.attrib.get('t')=='s' else v.text
        return result
    header=row_values(rows[0]); names={name:i for i,name in header.items()}
    observations=[]; published=set()
    for xmlrow in list(rows)[1:]:
        row=row_values(xmlrow)
        get=lambda name:row.get(names[name],'')
        metric={'NGDPD':'gdp','PPPGDP':'ppp'}.get(get('INDICATOR.ID'))
        if not metric:continue
        if get('SCALE')!='Billions':raise ValueError('Unexpected IMF scale')
        published.add(get('PUBLICATION_DATE'))
        actual_match=re.search(r'(?:19|20)\d{2}',get('LATEST_ACTUAL_ANNUAL_DATA'))
        actual=int(actual_match[0]) if actual_match else 0
        for i,year in header.items():
            if not year.isdigit() or int(year)<2022:continue
            value=row.get(i,'')
            try: value=decimal.Decimal(value)*decimal.Decimal(1000000000)
            except decimal.InvalidOperation:continue
            if not value.is_finite() or value<0:continue
            observations.append(dict(country=get('COUNTRY.ID'),name=get('COUNTRY'),indicator=metric,year=int(year),value=float(value),status='ACTUAL' if int(year)<=actual else 'ESTIMATE_OR_FORECAST'))
    if len(published)!=1 or len(observations)<500:raise ValueError('Incomplete or mixed IMF release')
    publication=published.pop()
    return dict(provider='imf',source='IMF WEO',publishedAt=publication,edition='WEO '+publication[:7],sourceUrl=url,
        retrievedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),checksum=hashlib.sha256(blob).hexdigest(),observations=observations)

if __name__=='__main__':
    page=download(PAGE).decode('utf-8')
    link=re.search(r'href="([^"]*/WEO(?:Apr|Oct)\d{4}all\.xlsx)"',page,re.I)
    if not link:raise ValueError('Official WEO workbook link not found')
    url=urllib.parse.urljoin(PAGE,link[1]); snapshot=parse(download(url),url)
    target=pathlib.Path(__file__).resolve().parents[1]/'src/main/resources/data/imf-weo.json'
    metadata={k:v for k,v in snapshot.items() if k!='observations'}
    content=json.dumps(metadata,ensure_ascii=False,indent=2)[:-2]+',\n  "observations": [\n'+',\n'.join('    '+json.dumps(row,ensure_ascii=False,separators=(',',':')) for row in snapshot['observations'])+'\n  ]\n}\n'
    temp=target.with_suffix('.tmp');temp.write_text(content,encoding='utf-8');temp.replace(target)
    print(snapshot['edition'],snapshot['publishedAt'],len(snapshot['observations']),'observations')

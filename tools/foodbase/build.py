# Builds final.txt (the rows of data/food/FoodSeed.kt) from the Hebrew lists in this folder.
# seed.txt  = Hebrew foods with estimated values; over.txt = which of them take their values from USDA;
# new*.txt  = more foods straight from USDA. "@id" is an FDC id, "~text" a description prefix.
# Needs sr.tsv: fdc_id, category, description, kcal, protein, carbs, fat per 100 g, one food per line,
# made from the USDA FoodData Central "SR Legacy" CSV download (food.csv + food_nutrient.csv). Set SR below.
import re,sys
SR='/tmp/claude-0/-home-claude/6d405ea6-d85a-53ce-8e85-f7ce636ccf30/scratchpad/usda/work/sr.tsv'
byid={};rows=[]
for l in open(SR,encoding='utf-8'):
    r=l.rstrip('\n').split('\t'); byid[r[0]]=r; rows.append(r)
def resolve(src,name):
    if src.startswith('@'):
        r=byid[src[1:]]
    elif src.startswith('~'):
        pat=re.compile('^'+src[1:],re.I)
        m=[r for r in rows if pat.search(r[2])]
        if not m:
            pat2=re.compile(re.escape(src[1:].rstrip('$')),re.I); m=[r for r in rows if pat2.search(r[2])]
        if not m: print('MISS',name,src); return None
        m.sort(key=lambda r:len(r[2])); r=m[0]
        print('RX',name,'->',r[2][:70],r[3])
    return r
def norm(s):
    s=re.sub('[֑-ׇ]','',s)
    s=s.replace('׳','').replace("'",'').replace('״','').replace('"','')
    s=s.replace(' אחוז','%'); s=re.sub(r'\s+%','%',s)
    return re.sub(r'\s+',' ',s).strip().lower()
over=dict(l.rstrip('\n').split('|',1) for l in open('over.txt',encoding='utf-8') if l.strip())
out=[];used=set()
for l in open('seed.txt',encoding='utf-8'):
    l=l.rstrip('\n')
    if not l.strip() or l.startswith('#'): continue
    n,k,p,c,f,por,u,al=l.split('|')
    src=0
    if n in over:
        used.add(n); r=resolve(over[n],n)
        if r:
            ok,op=float(k),float(r[3])
            if abs(ok-op)>max(25,0.2*ok): print('DIFF',n,'mine',k,'usda',r[3],r[2][:60])
            k,p,c,f=r[3],r[4],r[5],r[6]; src=1
    out.append([n,k,p,c,f,por,u,al,src])
print('unused overrides',set(over)-used)
for fn in ['new.txt','new2.txt','new3.txt']:
    for l in open(fn,encoding='utf-8'):
        l=l.rstrip('\n')
        if not l.strip() or l.startswith('#'): continue
        n,por,u,al,src=l.split('|')
        r=resolve(src,n)
        if r: out.append([n,r[3],r[4],r[5],r[6],por,u,al,1])
seen={};bad=0;final=[]
ALC=('בירה','יין','וודקה','קוקטייל','ליקר','תמצית וניל')
for o in out:
    names=[o[0]]+[a for a in o[7].split('§') if a]
    keep=[]
    for nm in names:
        key=norm(nm)
        if key in seen:
            if seen[key]!=o[0]: print('DUP',nm,'|',o[0],'|',seen[key]); bad+=1
            continue
        seen[key]=o[0]; keep.append(nm)
    k,p,c,f=[float(x) for x in o[1:5]]
    est=4*p+4*c+9*f
    if not o[0].startswith(ALC) and abs(est-k)>max(25,0.2*k): print('KCAL',o[0],k,round(est))
    if re.search('[A-Za-z]','|'.join(names)): print('ENG',o[0])
    def r1(x): 
        x=round(float(x),1); return str(int(x)) if x==int(x) else str(x)
    final.append('|'.join([o[0],str(int(round(k))),r1(p),r1(c),r1(f),o[5],o[6],'§'.join(keep[1:])]))
open('final.txt','w',encoding='utf-8').write('\n'.join(final))
print(len(final),'rows',len(seen),'names', sum(o[8] for o in out),'from usda',bad,'dups')

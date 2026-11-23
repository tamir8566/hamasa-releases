import sys
t = open(sys.argv[1]).read().split('\n\n')
keep = [b for b in t if 'com.tamir' in b or 'Main Thread' in b]
print('\n\n'.join(b[:6000] for b in keep[:4]))

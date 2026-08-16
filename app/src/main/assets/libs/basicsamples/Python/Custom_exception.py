class MyEx(Exception): pass
try:
    raise MyEx("boom")
except MyEx as e:
    print(e)

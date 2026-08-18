def parse(s):
    try:
        return int(s)
    except ValueError:
        print("bad")
        return 0
print(parse("12a"))

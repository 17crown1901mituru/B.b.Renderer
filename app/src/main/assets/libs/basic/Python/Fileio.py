with open("/tmp/py_sample.txt","w") as f:
    f.write("hello")
print(open("/tmp/py_sample.txt").read())

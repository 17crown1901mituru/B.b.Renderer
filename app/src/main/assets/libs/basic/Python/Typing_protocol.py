from typing import Protocol
class Greeter(Protocol):
    def greet(self) -> str: ...

class G:
    def greet(self): return "hi"
print(G().greet())

from typing import TypeVar, List
T = TypeVar("T")
def make_list(*items: T) -> List[T]:
    return list(items)
print(make_list(1,2,3))

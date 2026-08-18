var mySet = new Set();

mySet.add(1);
mySet.add(5);
mySet.add("some text");
var o = {a: 1, b: 2};
mySet.add(o);

mySet.has(1); // true

mySet.has(3); // false, 3 has not been added to the set

mySet.has(5);              // true

mySet.has(Math.sqrt(25));  // true

mySet.has("Some Text".toLowerCase()); // true

mySet.has(o); // true


mySet.size; // 4


mySet.delete(5); // removes 5 from the set

mySet.has(5);    // false, 5 has been removed


mySet.size; // 3, we just removed one value

for (let item of mySet) console.log(item);


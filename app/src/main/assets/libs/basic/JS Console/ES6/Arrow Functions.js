var func = x => x * x;                  // concise syntax, implied "return"

var func = (x, y) => { return x + y; }; // with block body, explicit "return" needed

// Easy array filtering, mapping, ...
var arr = [5, 6, 13, 0, 1, 18, 23];
var sum = arr.reduce((a, b) => a + b);  // 66

var even = arr.filter(v => v % 2 == 0); // [6, 0, 18]

var double = arr.map(v => v * 2);

console.log(sum);

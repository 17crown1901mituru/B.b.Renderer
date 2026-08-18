var sym1 = Symbol();
var sym2 = Symbol("foo");
var sym3 = Symbol("foo");

console.log(typeof sym1); // symbol

//Symbols are unique even with same label
console.log(Symbol("label") == Symbol("label"));  //false

// Global symbol registry
var sym = Symbol.for("key");
var key = Symbol.keyFor(sym);

console.log(key);  // key


//Symbols as property

var obj = {};
obj[Symbol("a")] = "a";
obj[Symbol.for("b")] = "b";

console.log(obj[Symbol.for("b")]); //b

//Get all properties set with symbols
console.log(Object.getOwnPropertySymbols(obj));



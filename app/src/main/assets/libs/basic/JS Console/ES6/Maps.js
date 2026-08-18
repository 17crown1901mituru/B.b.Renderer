var myMap = new Map();

var keyString = "a string",
    keyObj = {},
    keyFunc = function () {};

// setting the values

myMap.set(keyString, "value associated with 'a string'");
myMap.set(keyObj, "value associated with keyObj");
myMap.set(keyFunc, "value associated with keyFunc");

myMap.size; // 3


// getting the values

myMap.get(keyString);    // "value associated with 'a string'"

myMap.get(keyObj);       // "value associated with keyObj"

myMap.get(keyFunc);      // "value associated with keyFunc"


myMap.get("a string");   // "value associated with 'a string'"

                         // because keyString === 'a string'

myMap.get({});           // undefined, because keyObj !== {}

myMap.get(function() {}) // undefined, because keyFunc !== function () {}


for (var [key, value] of myMap) {
  console.log(key + " = " + value);
}

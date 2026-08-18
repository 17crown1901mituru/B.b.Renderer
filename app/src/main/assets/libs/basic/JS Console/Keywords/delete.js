// deletes properties and methods of an object

//Example 1
var object = new Object();
object.name = "Object";
console.log(object.name);
delete object.name;
console.log(object.name);

//Example 2

var arr = new Array(1,2,3);
console.log(arr);
delete Array;
var arr2 = new Array(4,5,6); //ReferenceError


//Example 3
delete(Math.sin);
console.log(Math.sin(30)); //TypeError

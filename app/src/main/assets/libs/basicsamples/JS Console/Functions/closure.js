var adder = Adder(2);
console.log(adder(5));
console.log(adder(5));
console.log(adder(3));


function Adder(def) {
var x = def;
 function add(y) {
 return x+= y;
 };
 return add;
}

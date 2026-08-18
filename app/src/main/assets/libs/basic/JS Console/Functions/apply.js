
var addition = add.apply(null,[5,2]);
console.log(addition);

var obj = {}
var addition = add.apply(obj,[5,2]);
console.log(addition);

function add(a,b){
   return a+b;
}

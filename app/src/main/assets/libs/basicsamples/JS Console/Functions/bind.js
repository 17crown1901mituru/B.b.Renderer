
var fun = add.bind(null,5);
var addition = fun(2);
console.log(addition);

function add(a,b){
   return a+b;
}

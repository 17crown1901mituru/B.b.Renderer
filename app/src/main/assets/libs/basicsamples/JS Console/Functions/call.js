var addition = add.call(null,5,2);
console.log(addition);

addition = add.call(this,6,9);
console.log(addition);


function add(a,b){
   return a+b;
}

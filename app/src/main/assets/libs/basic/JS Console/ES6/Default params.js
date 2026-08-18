let val = multiply(5); 
console.log(val);

function multiply(a, b = 3) {
  return a*b;
}
var object = {position:"One",color:"Red",length:10};

//Example 1
for (property in object){
console.log(property);
}

//Example 2
for (property in object){
console.log(object[property]);
}

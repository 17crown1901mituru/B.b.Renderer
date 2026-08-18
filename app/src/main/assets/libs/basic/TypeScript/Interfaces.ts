// How to create an interface

interface Animal {
    name: string;
   readonly color: string; //Makes it readonly
    isWild?:boolean; //Makes it optional
    speed:number;
}

let dog: Animal = {
    name : "Rex",
    color: "Brown",
    isWild: false,
    speed: 40
}
dog.color = "Black"; //Compile error
console.log(JSON.stringify(dog,null,4));






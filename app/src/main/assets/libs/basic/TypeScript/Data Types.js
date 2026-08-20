
// Strings

let firstName:string  = "Titus";
let codeType:string = 'TypeScript';
let multiline:string = ` This is
a muliline
String`;

// Numbers

let age:number = 10;
let count:number = 1e5;

// Booleans

let isOver:boolean = true;
let isLate:boolean = false;

// Array

// one type
let names: string[] = ["Kelvin","Samuel","Peter","Anna"];
// mixed types
let array: (string|number|boolean)[] = ["Sun",5,true,50,"Test",false];


// Tuple

let color:[string,number,number,number] = ["RED",255,0,0];
let description:[string,number,boolean] = ["John",5,true];


// Object

let student: object = { name: "Ruth", gender : "Female", age : 14 }
//you can specify object props types
let student2: { name: string; gender: string; age : number} = { name: "Thomas", gender : "Male", age : 16 }


// Function

let add:  (x: number, y: number) => number;
add = function(x:number,y:number): number{
    return x+y;
}


// Enum

 enum Day { Mon, Tue,Wed, Thur, Fri, Sat, Sun }
 let day = Day.Mon;
 if(day ==Day.Mon){
     console.log("Monday");
 }
 
 // Any
 
 let anyType : any;
 anyType = "Test";
 anyType = 5;
 anyType = true;
 
 
 // Void
 
 let nothing: void = undefined;
 
 function logText(text:string):void {
     console.log(text);
 }
 
 // Never
 
 function throwError(code: number,message: string): never {
    throw new Error(code +":"+message);
}

// Union

 
 let value: number | boolean;
 value = 55;
 value = false;
 
 // Alias
 
 type point  = number;
 let startPoint:point  = 0;
 
 type alphanumeric = string | number;
 let position: alphanumeric;
 position = 2;
 position = "first";
 position = 5;
 position = "last";
 
 
 // Literal
 
 let answer: 'Moon';
 answer = "Moon";
 
 let option: 'Take' | 'Leave' | 'Drop';
 option = "Take";
 option = "Leave";
 
 
 
 

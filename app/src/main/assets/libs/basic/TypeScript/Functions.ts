
//Define a normal function

let result:number = divide(4,2);
console.log(result);

function divide(value1:number,value2:number): number {
    return value1/value2;
}


// Optional params
multiply(1,2);
multiply(1,2,3);
function multiply(value1:number,value2:number,value3?:number): number {
    if (typeof value3 !== 'undefined') {
        return value1*value2*value3;
    }
    return value1*value2;
}

    
 // Default Params
 console.log(substract(undefined,5));
 function substract(value1:number = 20,value2:number): number {
    return value1-value2;
 }
 
 // Rest Params
 console.log(concat("One","Two","Three"));
 function concat(...names: string[]): string {
     return names.join("-");
 }
 
 
 // Overloading
 
 function add(x: number, y: number): number;
function add(x: string, y: string): string;
function add(x: any, y: any): any {
   return x + y;
}
 
 

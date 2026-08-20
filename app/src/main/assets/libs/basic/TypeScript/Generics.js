//Simple generic example

let arr : number[] = [1,2,3,4,5];
let item:<T> = getLastItem(arr);
console.log(item);
console.log(typeof item);

let arr : string[] = ["one","two","three","four","five"];
let item:<T> = getLastItem(arr);
console.log(item);
console.log(typeof item);


function getLastItem<T>(items: T[]): T {
    return items[items.length-1];
}


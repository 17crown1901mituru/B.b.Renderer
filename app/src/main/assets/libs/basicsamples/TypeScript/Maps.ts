// Create empty map

let map = new Map<string, string>();

// Map with initial values

let map2 = new Map<string, string>();
map2.set("key1","value1");
map2.set("key2","value2");
map2.set("key3","value3");

console.log(map2.get("key1"));
console.log(map2.size);

for (let key of map2.keys()) {
    console.log(key);
}

// Iterate over map values
for (let value of map2.values()) {
    console.log(value);
}

// Iterate over map entries
for (let entry of map2.entries()) {
    console.log(entry[0]+" "+entry[1]);
}

// Destructuring items
for (let [key, value] of map2) {
    console.log(key+"  "+value);
}
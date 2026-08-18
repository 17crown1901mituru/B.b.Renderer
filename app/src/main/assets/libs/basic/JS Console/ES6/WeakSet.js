var ws = new WeakSet();
var obj = {};
var foo = {};

ws.add(window);
ws.add(obj);

ws.has(window); // true

ws.has(foo);    // false, foo has not been added to the set


ws.delete(window); // removes window from the set

ws.has(window);    // false, window has been removed

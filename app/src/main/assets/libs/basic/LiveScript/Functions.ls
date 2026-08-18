# define a function
function add x,y 
 x + y

# another way define a function
add2 = (x, y) -> x + y

# an empty function
->

# function with no args
getName = -> "Livescript"

# suppress auto return with !
(x, y) !-> x + y

# You can ommit parentheses when calling a function
value = add 1,2
console.log value

# Call function with no args using !
console.log getName!

# Use do to call functions with no arguments:
console.log do -> 3 + 2


   


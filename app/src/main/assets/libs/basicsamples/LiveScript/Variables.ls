# Declare a variable 
var something

# Assign a variable
something = "something"

# Constant variable
const number = 5
number2 = 12
sum = number + number2
console.log sum
# Variable will not be modified in function
do ->
 something = "modified"
console.log something
  
# Use : to modify variables in outer scopes
do ->
 something := "modified"
console.log something


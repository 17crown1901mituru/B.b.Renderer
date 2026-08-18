# More examples at https://www.livescript.net


# Commas are not needed if the item preceding is not callable

 array = [1 2 3 true void \word 'hello there']
console.log JSON.stringify array

# List inside list

tree =
  * 1
    * 2
      3
    4
  * 5
    6
    * 7
      8
      * 9
        10
    11
    
console.log JSON.stringify tree
obj-list =
  * name: 'tessa'
    age:  23
  * name: 'kendall'
    age:  19
    
console.log JSON.stringify obj-list


# More examples at https://www.livescript.net


# Braces not needed

obj = {prop: 1, thing: 'moo'}


person =
  age:      23
  eye-color: 'green'
  height:   180cm

oneline = color: 'blue', heat: 4

# Dynamic keys

variable = \age
obj2 = 
 "#variable": 234,
  (person.eye-color): false
  
console.log JSON.stringify obj2
  
  
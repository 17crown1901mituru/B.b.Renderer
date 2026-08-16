# More examples at https://www.livescript.net

# Strict comparison

2 + 4 == 6      #=> true
\boom is 'boom' #=> true

\boom != null   #=> true
2 + 2 is not 4  #=> false
0 + 1 isnt 1    #=> false

# Fuzzy Comparison

2 ~= '2'       #=> true
\1 !~= 1       #=> false


# Chained comparison

1 < 2 < 4        #=> true
1 < 2 == 4/2 > 0 #=> true
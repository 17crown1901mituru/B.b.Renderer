   let array = ["One","Two","Three","Four","Five"];
   let len = array.length;
   //Example 1
   var i = 0;
   while (i<len)
   {
      //   console.log(array[i]);
      i++;
   }
   //Example 2
   i = 0;
   while ( true )
   {
      console.log(array[i]);
      i++;
       if (i>=len) break ;
   }
   
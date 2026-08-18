   let array = ["One","Two","Three","Four","Five"];
   let len = array.length;
   //Example 1
   for (let i = 0;i<len;i++)
   {
      console.log(array[i]);
   }
   //Example 2
   for (let i = len - 1;i>-1;i--)
   {
      console.log(array[i]);
   }
   //Example 3
   for (let i = 0;i<len;i++)
   {
       if (i == 2) continue ;
      console.log(array[i]);
   }
   //Example 4
   for (let i = 0;i<len;i++)
   {
       if (i == 3) break ;
      console.log(array[i]);
   }
   
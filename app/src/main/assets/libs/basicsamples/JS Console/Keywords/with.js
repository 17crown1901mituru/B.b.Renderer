   // Example 1
   var obj = 
   {
      name : "MyName",
      age : 235
   };
   with (obj)
   {
      console.log(name); // No need to write obj.name
      console.log(age);
   }
   // Example 2
   with (Math)
   {
      console.log(PI); // No need to write Math.PI
      console.log(sin(30));
   }
   

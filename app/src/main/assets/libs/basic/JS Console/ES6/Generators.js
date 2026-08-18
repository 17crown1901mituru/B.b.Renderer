    function  * valueGen()
   {
      var index = 0;
      while (index<3) yield index++;
   }
   var gen = valueGen();
   console.log(gen.next().value);
   // 0
   console.log(gen.next().value);
   // 1
   console.log(gen.next().value);
   // 2
   console.log(gen.next().value);
   // undefined
   
   
   console.log(" ");
   
   //Example 2, passing args
    function *logGenerator()
   {
      console.log( yield );
      console.log( yield );
      console.log( yield );
   }
   var gen = logGenerator();
   // the first call of next executes from the start of the function
   // until the first yield statement
   gen.next();
   gen.next('pretzel');
   // pretzel
   gen.next('california');
   // california
   gen.next('mayonnaise');
   // mayonnaise
   
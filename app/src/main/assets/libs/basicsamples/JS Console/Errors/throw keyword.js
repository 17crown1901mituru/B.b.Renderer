   try 
   {
      var d = divide(5,0);
      alert(d);
   }
    catch (e)
   {
      alert(e);
   }
    finally 
   {
     
   }

    function divide(num1,num2)
   {
       if (num2 == 0) throw  new Error("Cannot divide by zero");
       return num1 / num2;
   }
   

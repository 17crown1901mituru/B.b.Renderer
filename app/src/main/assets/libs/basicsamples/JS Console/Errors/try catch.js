   var a = 5;
   try 
   {
      var b = a + d; // d is not defined
   }
    catch (e)
   {
      alert(e);
   }

   
   try 
   {
      Math.calc(5); // No calc method in Math
   }
    catch (e)
   {
      alert(e);
   }
    finally 
   {
      
   }
   

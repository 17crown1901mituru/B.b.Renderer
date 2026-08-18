   var obj = 
   {
      name : "Dog",age : 5,isBlack: true 
   };

   console.log(obj.name);
   console.log(obj["name"]);
   console.log(obj.age);
   
   obj.age = 6;
   console.log(obj.age);
   
   console.log(Object.keys(obj));
   
   var obj2 = Object.create(obj);
   console.log(obj2.isBlack);
   

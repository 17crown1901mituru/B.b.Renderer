   var cat =  new Animal("Cat");
   console.log(cat.getName());

    function Animal(name)
   {
      var animalName = name;
       this .setName =  function (name)
      {
         animalName = name;
      };
       this .getName =  function (name)
      {
          return animalName;
      };
   }
   

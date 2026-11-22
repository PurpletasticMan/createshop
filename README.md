# CreateShop (Fabric, Minecraft 26.3)

Ops:   /createshop "<name>" <price> <cost item>     (hold the item to sell in your main hand)
       /removeshop <id>
Everyone: /shop   (lists shops, click [Buy])   or   /shop buy <id>

Examples:
  /createshop "Super Sword" 5 minecraft:diamond
  /createshop "Magic Apple" 64 minecraft:emerald
  /createshop "Rare Pickaxe" 20 somemod:silver_coin     <- a currency item from another mod

Build:  Java 25 required.   ./gradlew build   -> build/libs/createshop-1.0.0.jar
Install: put the jar + Fabric API in the mods folder of the server (players don't need it).

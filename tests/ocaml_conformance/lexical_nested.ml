module type ITEM = sig val value : unit -> int end
module type SHOP = sig module Inventory : ITEM val total : unit -> int end
module Store : SHOP = struct
  module type INTERNAL = sig val secret : unit -> int end
  module Inventory : ITEM = struct let value () = 12 end
  module Internal : INTERNAL = struct let secret () = 8 end
  let total () = Inventory.value () + Internal.secret ()
end
let () = print_endline (string_of_int (Store.Inventory.value () + Store.total ()))

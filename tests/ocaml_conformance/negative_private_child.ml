module type ITEM = sig val value : unit -> int end
module type SHOP = sig module Public : ITEM end
module Store : SHOP = struct
  module Public : ITEM = struct let value () = 5 end
  module Private : ITEM = struct let value () = 8 end
end
let () = print_endline (string_of_int (Store.Private.value ()))

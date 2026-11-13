module type TOKEN = sig
  type token
  val issue : int -> token
  val inspect : token -> int
end
module Warehouse : TOKEN = struct
  type token = int
  let issue x = x
  let inspect x = x
end
module type STOCK = sig
  include TOKEN
  val available : token -> int
end
module Inventory : STOCK with type token = Warehouse.token = struct
  include Warehouse
  let available x = if Warehouse.inspect x > 0 then 1 else 0
end
let () = print_endline (string_of_int (Warehouse.inspect (Inventory.issue 11)))

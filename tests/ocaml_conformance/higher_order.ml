module type SOURCE = sig val get : unit -> int end
module Seed : SOURCE = struct let get () = 5 end
module type RESULT = sig val result : unit -> int end
module type MAKER = functor (S : SOURCE) -> RESULT
module Make (S : SOURCE) : RESULT = struct let result () = S.get () end
module Decorate (F : MAKER) : RESULT = struct
  module Inner = F(Seed)
  let result () = Inner.result () + 1
end
module D = Decorate(Make)
let () = print_endline (string_of_int (D.result ()))

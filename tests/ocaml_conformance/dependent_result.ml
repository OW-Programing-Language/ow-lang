module type BOX = sig
  type token
  val wrap : int -> token
  val unwrap : token -> int
end
module First : BOX = struct
  type token = int
  let wrap x = x
  let unwrap x = x
end
module Forward (Source : BOX) : BOX with type token = Source.token = struct
  type token = Source.token
  let wrap x = Source.wrap x
  let unwrap x = Source.unwrap x
end
module F = Forward(First)
let () = print_endline (string_of_int (First.unwrap (F.wrap 7)))

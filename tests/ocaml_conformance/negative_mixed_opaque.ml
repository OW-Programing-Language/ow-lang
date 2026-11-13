module type BOX = sig
  type token
  val wrap : int -> token
  val unwrap : token -> int
end
module A : BOX = struct
  type token = int
  let wrap x = x
  let unwrap x = x
end
module B : BOX = struct
  type token = int
  let wrap x = x
  let unwrap x = x
end
let () = print_endline (string_of_int (B.unwrap (A.wrap 3)))

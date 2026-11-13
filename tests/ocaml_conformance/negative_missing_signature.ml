module type API = sig val get : unit -> int end
module Bad : API = struct let different () = 7 end
let () = print_endline "0"

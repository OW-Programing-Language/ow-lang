module type READ = sig val get : unit -> int end
module Store = struct let get () = 42 let secret () = 99 end
let shelf : (module READ) list = [ (module Store : READ) ]
let () =
  let module Reader = (val List.hd shelf : READ) in
  print_endline (string_of_int (Reader.get ()))

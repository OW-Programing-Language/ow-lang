module type READ = sig val read : int -> int end
module Shop = struct
  module Storage : READ = struct let read value = value + 3 end
end
module Storage = Shop.Storage
module type RESULT = sig val score : int -> int end
module MakeScore (Source : READ) : RESULT = struct
  let score value = Source.read value * 2
end
module Report = MakeScore(Storage)
let () = print_endline (string_of_int (Report.score 4))

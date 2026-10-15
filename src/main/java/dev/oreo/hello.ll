; ModuleID = 'hello_exe'
declare i32 @printf(i8*, ...)

@fmt = private constant [4 x i8] c"%d\0A\00"

define i32 @main() {
entry:
  %x = alloca i32
  store i32 5, i32* %x
  %t0 = load i32, i32* %x
  %t1 = add i32 %t0, 2
  %t2 = getelementptr [4 x i8], [4 x i8]* @fmt, i32 0, i32 0
  call i32 (i8*, ...) @printf(i8* %t2, i32 %t1)
  ret i32 0
}

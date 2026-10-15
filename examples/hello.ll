; ModuleID = 'hello_exe'
declare i32 @printf(i8*, ...)
@.fmt_int = private constant [4 x i8] c"%d\0A\00"
@.fmt_str = private constant [4 x i8] c"%s\0A\00"
@.str.0 = private constant [11 x i8] c"hello, ow!\00"
@.str.1 = private constant [11 x i8] c"big number\00"
@.str.2 = private constant [13 x i8] c"small number\00"

define i32 @add(i32 %a.arg, i32 %b.arg) {
entry:
  %a = alloca i32
  store i32 %a.arg, i32* %a
  %b = alloca i32
  store i32 %b.arg, i32* %b
  %t0 = load i32, i32* %a
  %t1 = load i32, i32* %b
  %t2 = add i32 %t0, %t1
  ret i32 %t2
after.return.0:
  ret i32 0
}

define i32 @fact(i32 %n.arg) {
entry:
  %n = alloca i32
  store i32 %n.arg, i32* %n
  %t0 = load i32, i32* %n
  %t1 = icmp sle i32 %t0, 1
  br i1 %t1, label %if.then.0, label %if.else.0
if.then.0:
  ret i32 1
after.return.1:
  br label %if.end.0
if.else.0:
  %t2 = load i32, i32* %n
  %t3 = load i32, i32* %n
  %t4 = sub i32 %t3, 1
  %t5 = call i32 @fact(i32 %t4)
  %t6 = mul i32 %t2, %t5
  ret i32 %t6
after.return.2:
  br label %if.end.0
if.end.0:
  ret i32 0
}

define i32 @main() {
entry:
  %t0 = getelementptr [11 x i8], [11 x i8]* @.str.0, i32 0, i32 0
  %greeting = alloca i8*
  store i8* %t0, i8** %greeting
  %t1 = load i8*, i8** %greeting
  %t2 = getelementptr [4 x i8], [4 x i8]* @.fmt_str, i32 0, i32 0
  call i32 (i8*, ...) @printf(i8* %t2, i8* %t1)
  %t3 = call i32 @add(i32 3, i32 4)
  %sum = alloca i32
  store i32 %t3, i32* %sum
  %t4 = load i32, i32* %sum
  %t5 = getelementptr [4 x i8], [4 x i8]* @.fmt_int, i32 0, i32 0
  call i32 (i8*, ...) @printf(i8* %t5, i32 %t4)
  %x = alloca i32
  store i32 10, i32* %x
  %t6 = load i32, i32* %x
  %t7 = icmp sgt i32 %t6, 5
  br i1 %t7, label %if.then.0, label %if.else.0
if.then.0:
  %t8 = getelementptr [11 x i8], [11 x i8]* @.str.1, i32 0, i32 0
  %t9 = getelementptr [4 x i8], [4 x i8]* @.fmt_str, i32 0, i32 0
  call i32 (i8*, ...) @printf(i8* %t9, i8* %t8)
  br label %if.end.0
if.else.0:
  %t10 = getelementptr [13 x i8], [13 x i8]* @.str.2, i32 0, i32 0
  %t11 = getelementptr [4 x i8], [4 x i8]* @.fmt_str, i32 0, i32 0
  call i32 (i8*, ...) @printf(i8* %t11, i8* %t10)
  br label %if.end.0
if.end.0:
  %i = alloca i32
  store i32 0, i32* %i
  br label %while.cond.1
while.cond.1:
  %t12 = load i32, i32* %i
  %t13 = icmp slt i32 %t12, 5
  br i1 %t13, label %while.body.1, label %while.end.1
while.body.1:
  %t14 = load i32, i32* %i
  %t15 = getelementptr [4 x i8], [4 x i8]* @.fmt_int, i32 0, i32 0
  call i32 (i8*, ...) @printf(i8* %t15, i32 %t14)
  %t16 = load i32, i32* %i
  %t17 = add i32 %t16, 1
  store i32 %t17, i32* %i
  br label %while.cond.1
while.end.1:
  %t18 = call i32 @fact(i32 6)
  %t19 = getelementptr [4 x i8], [4 x i8]* @.fmt_int, i32 0, i32 0
  call i32 (i8*, ...) @printf(i8* %t19, i32 %t18)
  ret i32 0
}

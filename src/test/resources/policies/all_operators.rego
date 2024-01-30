package all_operators

import future.keywords.if
import future.keywords.in

allow if {
	input.entity.a == 1
	input.entity.b == { "foo", "bar" }
	false in input.entity.c
    input.entity.d != 2
    input.entity.e > 3
    count(input.entity.f) == 4
    abs(input.entity.g) == 5
    ceil(input.entity.h) == 6
    input.entity.i + 1 == 7
    input.entity.j % 8 == 8
    input.entity.k == 0.912
    max(input.entity.l) < 10
    sort(input.entity.m) == [ "a", "b" ]
    sum(input.entity.n) == 11
    startswith("foo", input.entity.o) == true
}

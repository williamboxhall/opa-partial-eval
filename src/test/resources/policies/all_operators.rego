package all_operators

import future.keywords.if
import future.keywords.in

allow if {
	input.entity.a == 1
	input.entity.b == { "bar", "foo" }
	input.entity.b2 == [ "foo", "bar" ]
	false in input.entity.c
    input.entity.d != 2
    input.entity.e > 3
    input.entity.i + 1 == 7
    3 + input.entity.i3 == 73
    input.entity.i2 + 2 > 72
    count(input.entity.f) == 4
    count(input.entity.f2) > 42
    count(input.entity.f3) + 3 == 43
    count(input.entity.f4) + 4 < 44
    abs(input.entity.g) == 5
    abs(input.entity.g2) > 52
    ceil(input.entity.h) == 6
    ceil(input.entity.h2) > 62
    input.entity.i4 + 3 + 4 + 5 == 72
    input.entity.i5 + 3 + 4 + 5 > 73
    input.entity.j % 8 == 0
    input.entity.k == 0.912
    max(input.entity.l) < 10
    sort(input.entity.m) == [ "a", "b" ]
    sum(input.entity.n) == 11
    sum(input.entity.n2) + 1 == 12
    startswith("foo", input.entity.o) == true
}

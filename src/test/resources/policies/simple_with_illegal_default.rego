package simple_with_illegal_default

import rego.v1

default allow := true

allow if {
	input.entity.a == 1
}

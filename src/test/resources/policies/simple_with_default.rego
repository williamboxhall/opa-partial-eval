package simple_with_default

import rego.v1

default allow := false

allow if {
	input.entity.a == 1
}

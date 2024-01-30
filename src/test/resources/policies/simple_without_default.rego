package simple_without_default

import rego.v1

allow if {
	input.entity.a == 1
}

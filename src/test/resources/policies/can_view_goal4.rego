package goals4

import future.keywords.if

default allow := false

allow if {
	input.current_user.permission == "view_all_goals"
}

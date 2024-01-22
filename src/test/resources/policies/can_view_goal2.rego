package goals2

import future.keywords.if
import future.keywords.in

allow if {
	"view_all_goals" in input.current_user.permissions
}

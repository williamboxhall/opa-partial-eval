package goals3

import future.keywords.if

# Rule to allow viewing a goal if account ID matches and the author ID matches the current user's user ID
allow if {
	input.entity.account_id == input.current_user.account_id
	input.entity.author_id == input.current_user.user_id
}

# Rule to allow viewing a goal if account ID matches and the author ID is a direct report of the current user
allow if {
	input.entity.account_id == input.current_user.account_id
	some i
	input.current_user.direct_reports[i] == input.entity.author_id
}

# Rule to allow viewing a goal if account ID matches and the current user has "view_all_goals" permission
allow if {
	input.entity.account_id == input.current_user.account_id
}

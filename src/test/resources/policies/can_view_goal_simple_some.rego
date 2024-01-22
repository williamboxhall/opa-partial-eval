package goals

import future.keywords.if


allow if {
    input.entity.account_id == input.current_user.account_id
	some i
	input.current_user.direct_reports[i] == input.entity.author_id
}

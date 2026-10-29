package com.vcrd.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.vcrd.constants.Role;

/**
 * Who performs which workflow step, configured in application.yml (vcrd.workflow.*) so the business
 * can change it without code changes.
 *
 * @param zparkRoles     roles that may initiate Z PARK
 * @param miroRoles      roles that may initiate MIRO posting
 * @param approvalLevels approval chain in order; a level applies when the invoice total is at least its minAmount
 */
@ConfigurationProperties(prefix = "vcrd.workflow")
public record WorkflowProperties(List<Role> zparkRoles, List<Role> miroRoles, List<ApprovalLevel> approvalLevels) {

    public record ApprovalLevel(String name, List<Role> roles, BigDecimal minAmount) {

        boolean appliesTo(BigDecimal total) {
            return minAmount == null || (total != null && total.compareTo(minAmount) >= 0);
        }
    }

    public WorkflowProperties {
        zparkRoles = zparkRoles == null || zparkRoles.isEmpty() ? List.of(Role.PMO) : List.copyOf(zparkRoles);
        miroRoles = miroRoles == null || miroRoles.isEmpty() ? List.of(Role.PMO, Role.FINANCE) : List.copyOf(miroRoles);
        approvalLevels = approvalLevels == null || approvalLevels.isEmpty()
                ? List.of(new ApprovalLevel("Approval", List.of(Role.APPROVER), BigDecimal.ZERO))
                : List.copyOf(approvalLevels);
    }

    /** The approval levels that apply to an invoice of this total, in order. Always at least one. */
    public List<ApprovalLevel> levelsFor(BigDecimal total) {
        List<ApprovalLevel> levels = approvalLevels.stream().filter(l -> l.appliesTo(total)).toList();
        return levels.isEmpty() ? List.of(approvalLevels.get(0)) : levels;
    }

    /** Every role that appears at any approval level. */
    public Set<Role> allApproverRoles() {
        return approvalLevels.stream().flatMap(l -> l.roles().stream()).collect(Collectors.toSet());
    }
}

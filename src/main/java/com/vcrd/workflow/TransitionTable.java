package com.vcrd.workflow;

import static com.vcrd.invoice.InvoiceStatus.APPROVED;
import static com.vcrd.invoice.InvoiceStatus.DRAFT;
import static com.vcrd.invoice.InvoiceStatus.PENDING_APPROVAL;
import static com.vcrd.invoice.InvoiceStatus.REJECTED;
import static com.vcrd.invoice.InvoiceStatus.RETURNED;
import static com.vcrd.invoice.InvoiceStatus.SUBMITTED;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.vcrd.identity.Role;
import com.vcrd.invoice.InvoiceStatus;

/**
 * Allowed status transitions and who may trigger them.
 *
 * INTERIM FLOW: Draft -> Submitted -> (PMO review) -> Pending Approval -> Approved/Rejected/Returned.
 * Validation, JACK PARK and SAP stages slot in here once the business confirms the sequence (BRD Q3).
 * Intended to move to a database table so the sequence can change without code changes.
 */
@Component
public class TransitionTable {

    public record Transition(InvoiceStatus to, Set<Role> allowedRoles) {
    }

    private final Map<InvoiceStatus, Map<WorkflowAction, Transition>> table = new EnumMap<>(InvoiceStatus.class);

    public TransitionTable() {
        add(DRAFT, WorkflowAction.SUBMIT, SUBMITTED, Role.SITE_USER, Role.SITE_MANAGER);
        add(RETURNED, WorkflowAction.SUBMIT, SUBMITTED, Role.SITE_USER, Role.SITE_MANAGER);

        add(SUBMITTED, WorkflowAction.SEND_FOR_APPROVAL, PENDING_APPROVAL, Role.PMO);
        add(SUBMITTED, WorkflowAction.RETURN, RETURNED, Role.PMO);

        add(PENDING_APPROVAL, WorkflowAction.APPROVE, APPROVED, Role.APPROVER);
        add(PENDING_APPROVAL, WorkflowAction.REJECT, REJECTED, Role.APPROVER);
        add(PENDING_APPROVAL, WorkflowAction.RETURN, RETURNED, Role.APPROVER);
    }

    public Optional<Transition> find(InvoiceStatus from, WorkflowAction action) {
        return Optional.ofNullable(table.getOrDefault(from, Map.of()).get(action));
    }

    public Map<WorkflowAction, Transition> from(InvoiceStatus status) {
        return table.getOrDefault(status, Map.of());
    }

    private void add(InvoiceStatus from, WorkflowAction action, InvoiceStatus to, Role... roles) {
        table.computeIfAbsent(from, s -> new EnumMap<>(WorkflowAction.class))
                .put(action, new Transition(to, Set.of(roles)));
    }
}

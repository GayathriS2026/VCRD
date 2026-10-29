package com.vcrd.component;

import static com.vcrd.constants.InvoiceStatus.APPROVED;
import static com.vcrd.constants.InvoiceStatus.AWAITING_CLEARING;
import static com.vcrd.constants.InvoiceStatus.AWAITING_GR;
import static com.vcrd.constants.InvoiceStatus.CLEARED;
import static com.vcrd.constants.InvoiceStatus.MIRO_IN_PROGRESS;
import static com.vcrd.constants.InvoiceStatus.MIRO_UNCERTAIN;
import static com.vcrd.constants.InvoiceStatus.PENDING_APPROVAL;
import static com.vcrd.constants.InvoiceStatus.PENDING_VERIFICATION;
import static com.vcrd.constants.InvoiceStatus.READY_FOR_VALIDATION;
import static com.vcrd.constants.InvoiceStatus.REJECTED;
import static com.vcrd.constants.InvoiceStatus.RETURNED;
import static com.vcrd.constants.InvoiceStatus.UPLOADED;
import static com.vcrd.constants.InvoiceStatus.VALIDATION_FAILED;
import static com.vcrd.constants.InvoiceStatus.VALIDATION_PASSED;
import static com.vcrd.constants.InvoiceStatus.ZPARK_CREATED;
import static com.vcrd.constants.InvoiceStatus.ZPARK_IN_PROGRESS;
import static com.vcrd.constants.InvoiceStatus.ZPARK_UNCERTAIN;
import static com.vcrd.constants.WorkflowAction.APPROVE;
import static com.vcrd.constants.WorkflowAction.CLEARING_CONFIRMED;
import static com.vcrd.constants.WorkflowAction.FAIL_VALIDATION;
import static com.vcrd.constants.WorkflowAction.GR_CONFIRMED;
import static com.vcrd.constants.WorkflowAction.GR_NOT_REQUIRED;
import static com.vcrd.constants.WorkflowAction.GR_PENDING;
import static com.vcrd.constants.WorkflowAction.INITIATE_ZPARK;
import static com.vcrd.constants.WorkflowAction.OVERRIDE_EXCEPTION;
import static com.vcrd.constants.WorkflowAction.PASS_VALIDATION;
import static com.vcrd.constants.WorkflowAction.POST_MIRO;
import static com.vcrd.constants.WorkflowAction.RECONCILED_FOUND;
import static com.vcrd.constants.WorkflowAction.RECONCILED_NOT_FOUND;
import static com.vcrd.constants.WorkflowAction.REJECT;
import static com.vcrd.constants.WorkflowAction.RETURN;
import static com.vcrd.constants.WorkflowAction.SAP_CONFIRMED;
import static com.vcrd.constants.WorkflowAction.SAP_REJECTED;
import static com.vcrd.constants.WorkflowAction.SAP_UNCERTAIN;
import static com.vcrd.constants.WorkflowAction.SUBMIT;
import static com.vcrd.constants.WorkflowAction.VERIFY;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.vcrd.constants.InvoiceStatus;
import com.vcrd.constants.Role;
import com.vcrd.constants.WorkflowAction;

/**
 * Allowed status transitions and who may trigger them, following the agreed VIMP workflow.
 * Transitions with no roles are system-only (validation engine / SAP connector outcomes).
 * Intended to move to a database table so the sequence can change without code changes.
 */
@Component
public class TransitionTable {

    public record Transition(InvoiceStatus to, Set<Role> allowedRoles) {

        public boolean isSystemOnly() {
            return allowedRoles.isEmpty();
        }
    }

    private static final Role[] SITE = { Role.SITE_USER, Role.SITE_MANAGER };
    private static final Role[] SYSTEM = {};

    private final Map<InvoiceStatus, Map<WorkflowAction, Transition>> table = new EnumMap<>(InvoiceStatus.class);

    public TransitionTable() {
        // Phase 2-3: submit, then user verifies extracted fields
        add(UPLOADED, SUBMIT, PENDING_VERIFICATION, SITE);
        add(RETURNED, SUBMIT, PENDING_VERIFICATION, SITE);
        add(VALIDATION_FAILED, SUBMIT, PENDING_VERIFICATION, SITE);
        add(PENDING_VERIFICATION, VERIFY, READY_FOR_VALIDATION, SITE);

        // Phase 4: matching against SAP PO / GR data
        add(READY_FOR_VALIDATION, PASS_VALIDATION, VALIDATION_PASSED, SYSTEM);
        add(READY_FOR_VALIDATION, FAIL_VALIDATION, VALIDATION_FAILED, SYSTEM);
        add(VALIDATION_FAILED, OVERRIDE_EXCEPTION, VALIDATION_PASSED, Role.PMO);
        add(VALIDATION_FAILED, REJECT, REJECTED, Role.PMO);

        // Phase 5: Z PARK
        add(VALIDATION_PASSED, INITIATE_ZPARK, ZPARK_IN_PROGRESS, Role.PMO);
        add(ZPARK_IN_PROGRESS, SAP_CONFIRMED, ZPARK_CREATED, SYSTEM);
        add(ZPARK_IN_PROGRESS, SAP_UNCERTAIN, ZPARK_UNCERTAIN, SYSTEM);
        add(ZPARK_IN_PROGRESS, SAP_REJECTED, VALIDATION_FAILED, SYSTEM);
        add(ZPARK_UNCERTAIN, RECONCILED_FOUND, ZPARK_CREATED, SYSTEM);
        add(ZPARK_UNCERTAIN, RECONCILED_NOT_FOUND, VALIDATION_PASSED, SYSTEM);

        // Phase 6: goods receipt
        add(ZPARK_CREATED, GR_CONFIRMED, PENDING_APPROVAL, SYSTEM);
        add(ZPARK_CREATED, GR_NOT_REQUIRED, PENDING_APPROVAL, SYSTEM);
        add(ZPARK_CREATED, GR_PENDING, AWAITING_GR, SYSTEM);
        add(AWAITING_GR, GR_CONFIRMED, PENDING_APPROVAL, SYSTEM);

        // Phase 7: approvals, then MIRO
        add(PENDING_APPROVAL, APPROVE, APPROVED, Role.APPROVER);
        add(PENDING_APPROVAL, REJECT, REJECTED, Role.APPROVER);
        add(PENDING_APPROVAL, RETURN, RETURNED, Role.APPROVER, Role.PMO);
        add(APPROVED, POST_MIRO, MIRO_IN_PROGRESS, Role.PMO, Role.FINANCE);
        add(MIRO_IN_PROGRESS, SAP_CONFIRMED, AWAITING_CLEARING, SYSTEM);
        add(MIRO_IN_PROGRESS, SAP_UNCERTAIN, MIRO_UNCERTAIN, SYSTEM);
        add(MIRO_IN_PROGRESS, SAP_REJECTED, APPROVED, SYSTEM);
        add(MIRO_UNCERTAIN, RECONCILED_FOUND, AWAITING_CLEARING, SYSTEM);
        add(MIRO_UNCERTAIN, RECONCILED_NOT_FOUND, APPROVED, SYSTEM);

        // Phase 8: clearing
        add(AWAITING_CLEARING, CLEARING_CONFIRMED, CLEARED, SYSTEM);
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

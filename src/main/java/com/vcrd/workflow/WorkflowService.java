package com.vcrd.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vcrd.audit.AuditService;
import com.vcrd.common.BusinessException;
import com.vcrd.invoice.Invoice;
import com.vcrd.invoice.InvoiceAccessPolicy;
import com.vcrd.invoice.InvoiceDocumentRepository;
import com.vcrd.invoice.InvoiceDtos.InvoiceResponse;
import com.vcrd.invoice.InvoiceService;
import com.vcrd.invoice.InvoiceStateChanger;
import com.vcrd.invoice.InvoiceStatus;
import com.vcrd.security.CurrentUser;
import com.vcrd.security.CurrentUserProvider;
import com.vcrd.workflow.TransitionTable.Transition;

/**
 * Single entry point for invoice status changes. Enforces, in order:
 * data scope, allowed transition, role, guards (documents, comments, segregation of duties).
 * Concurrent actions on the same invoice are caught by the @Version optimistic lock.
 */
@Service
public class WorkflowService {

    private static final Set<WorkflowAction> REQUIRE_REMARK = Set.of(WorkflowAction.REJECT, WorkflowAction.RETURN);
    private static final Set<WorkflowAction> DECISIONS = Set.of(WorkflowAction.APPROVE, WorkflowAction.REJECT);

    private final InvoiceAccessPolicy accessPolicy;
    private final InvoiceStateChanger stateChanger;
    private final InvoiceDocumentRepository documents;
    private final InvoiceService invoiceService;
    private final TransitionTable transitions;
    private final WorkflowHistoryRepository history;
    private final AuditService audit;
    private final CurrentUserProvider currentUser;

    public WorkflowService(InvoiceAccessPolicy accessPolicy, InvoiceStateChanger stateChanger,
            InvoiceDocumentRepository documents, InvoiceService invoiceService, TransitionTable transitions,
            WorkflowHistoryRepository history, AuditService audit, CurrentUserProvider currentUser) {
        this.accessPolicy = accessPolicy;
        this.stateChanger = stateChanger;
        this.documents = documents;
        this.invoiceService = invoiceService;
        this.transitions = transitions;
        this.history = history;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional
    public InvoiceResponse perform(UUID invoiceId, WorkflowAction action, String remark) {
        CurrentUser user = currentUser.get();
        Invoice invoice = accessPolicy.loadAccessible(invoiceId, user);
        InvoiceStatus from = invoice.getStatus();

        Transition transition = transitions.find(from, action)
                .orElseThrow(() -> new BusinessException("INVALID_TRANSITION",
                        "Action " + action + " is not allowed when invoice is " + from));
        if (!user.hasAnyRole(transition.allowedRoles())) {
            throw new AccessDeniedException("Role not permitted for " + action);
        }
        checkGuards(invoice, action, remark, user);

        stateChanger.apply(invoice, transition.to());
        history.save(new WorkflowHistory(invoiceId, from, transition.to(), action, user.id(), remark));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", action.name());
        details.put("from", from.name());
        details.put("to", transition.to().name());
        if (remark != null) {
            details.put("remark", remark);
        }
        audit.record("INVOICE", invoiceId, "STATUS_CHANGED", details);
        return invoiceService.toResponse(invoice);
    }

    @Transactional(readOnly = true)
    public List<WorkflowHistory> history(UUID invoiceId) {
        accessPolicy.loadAccessible(invoiceId, currentUser.get());
        return history.findByInvoiceIdOrderByOccurredAt(invoiceId);
    }

    /** Actions the current user could perform on the invoice right now (for enabling UI buttons). */
    @Transactional(readOnly = true)
    public List<WorkflowAction> availableActions(UUID invoiceId) {
        CurrentUser user = currentUser.get();
        Invoice invoice = accessPolicy.loadAccessible(invoiceId, user);
        return transitions.from(invoice.getStatus()).entrySet().stream()
                .filter(e -> user.hasAnyRole(e.getValue().allowedRoles()))
                .filter(e -> !DECISIONS.contains(e.getKey()) || !invoice.getCreatedBy().equals(user.id()))
                .map(Map.Entry::getKey)
                .toList();
    }

    private void checkGuards(Invoice invoice, WorkflowAction action, String remark, CurrentUser user) {
        if (action == WorkflowAction.SUBMIT && documents.countByInvoiceId(invoice.getId()) == 0) {
            throw new BusinessException("DOCUMENT_REQUIRED", "Upload the invoice document before submitting");
        }
        if (REQUIRE_REMARK.contains(action) && (remark == null || remark.isBlank())) {
            throw new BusinessException("REMARK_REQUIRED", "A remark is required for " + action);
        }
        if (DECISIONS.contains(action) && invoice.getCreatedBy().equals(user.id())) {
            throw new BusinessException("SEGREGATION_OF_DUTIES", "You cannot approve or reject your own invoice");
        }
    }
}

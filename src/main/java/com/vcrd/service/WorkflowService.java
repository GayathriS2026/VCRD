package com.vcrd.service;

import com.vcrd.dto.InvoiceDtos;
import com.vcrd.constants.Role;
import com.vcrd.component.TransitionTable;
import com.vcrd.constants.WorkflowAction;
import com.vcrd.entity.WorkflowHistory;
import com.vcrd.repository.WorkflowHistoryRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vcrd.service.AuditService;
import com.vcrd.exception.BusinessException;
import com.vcrd.exception.NotFoundException;
import com.vcrd.repository.InvoiceRepository;
import com.vcrd.entity.Invoice;
import com.vcrd.service.InvoiceAccessPolicy;
import com.vcrd.repository.InvoiceDocumentRepository;
import com.vcrd.dto.InvoiceDtos.InvoiceResponse;
import com.vcrd.service.InvoiceService;
import com.vcrd.service.InvoiceStateChanger;
import com.vcrd.constants.InvoiceStatus;
import com.vcrd.dto.CurrentUser;
import com.vcrd.component.CurrentUserProvider;
import com.vcrd.component.TransitionTable.Transition;

/**
 * Single entry point for invoice status changes. Enforces, in order:
 * data scope, allowed transition, role, guards (documents, comments, segregation of duties).
 * Concurrent actions on the same invoice are caught by the @Version optimistic lock.
 */
@Service
public class WorkflowService {

    private static final Set<WorkflowAction> REQUIRE_REMARK = Set.of(WorkflowAction.REJECT, WorkflowAction.RETURN,
            WorkflowAction.OVERRIDE_EXCEPTION);
    private static final Set<WorkflowAction> DECISIONS = Set.of(WorkflowAction.APPROVE, WorkflowAction.REJECT,
            WorkflowAction.OVERRIDE_EXCEPTION);

    private final InvoiceAccessPolicy accessPolicy;
    private final InvoiceRepository invoices;
    private final InvoiceStateChanger stateChanger;
    private final InvoiceDocumentRepository documents;
    private final InvoiceService invoiceService;
    private final TransitionTable transitions;
    private final WorkflowHistoryRepository history;
    private final AuditService audit;
    private final CurrentUserProvider currentUser;

    public WorkflowService(InvoiceAccessPolicy accessPolicy, InvoiceRepository invoices, InvoiceStateChanger stateChanger,
            InvoiceDocumentRepository documents, InvoiceService invoiceService, TransitionTable transitions,
            WorkflowHistoryRepository history, AuditService audit, CurrentUserProvider currentUser) {
        this.accessPolicy = accessPolicy;
        this.invoices = invoices;
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
        Transition transition = findTransition(invoice, action);
        if (transition.isSystemOnly()) {
            throw new AccessDeniedException(action + " is recorded by the system and cannot be performed by users");
        }
        if (!user.hasAnyRole(transition.allowedRoles())) {
            throw new AccessDeniedException("Role not permitted for " + action);
        }
        checkGuards(invoice, action, remark, user);
        return apply(invoice, action, transition, remark, user.id());
    }

    /**
     * Records an outcome reported by the validation engine or the SAP connector
     * (e.g. SAP_CONFIRMED, GR_PENDING). Not exposed through the API. History shows no actor.
     */
    @Transactional
    public InvoiceResponse performSystem(UUID invoiceId, WorkflowAction action, String remark) {
        Invoice invoice = invoices.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice " + invoiceId + " not found"));
        Transition transition = findTransition(invoice, action);
        if (!transition.isSystemOnly()) {
            throw new IllegalArgumentException(action + " is a user action");
        }
        return apply(invoice, action, transition, remark, null);
    }

    private Transition findTransition(Invoice invoice, WorkflowAction action) {
        InvoiceStatus from = invoice.getStatus();
        return transitions.find(from, action)
                .orElseThrow(() -> new BusinessException("INVALID_TRANSITION",
                        "Action " + action + " is not allowed when invoice is " + from));
    }

    private InvoiceResponse apply(Invoice invoice, WorkflowAction action, Transition transition, String remark,
            UUID actorId) {
        UUID invoiceId = invoice.getId();
        InvoiceStatus from = invoice.getStatus();
        stateChanger.apply(invoice, transition.to());
        history.save(new WorkflowHistory(invoiceId, from, transition.to(), action, actorId, remark));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", action.name());
        details.put("from", from.name());
        details.put("to", transition.to().name());
        if (remark != null) {
            details.put("remark", remark);
        }
        if (actorId == null) {
            audit.recordSystem("INVOICE", invoiceId, "STATUS_CHANGED", details);
        } else {
            audit.record("INVOICE", invoiceId, "STATUS_CHANGED", details);
        }
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

package com.vcrd.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.vcrd.component.SapInvoiceGateway;
import com.vcrd.component.SapInvoiceGateway.Clearing;
import com.vcrd.component.SapInvoiceGateway.GoodsReceipt;
import com.vcrd.component.SapInvoiceGateway.ParkLine;
import com.vcrd.component.SapInvoiceGateway.ParkRequest;
import com.vcrd.component.SapInvoiceGateway.PostRequest;
import com.vcrd.component.SapInvoiceGateway.SapDocument;
import com.vcrd.component.SapInvoiceGateway.SapRejectedException;
import com.vcrd.constants.InvoiceStatus;
import com.vcrd.constants.MatchStatus;
import com.vcrd.constants.WorkflowAction;
import com.vcrd.dto.ValidationDtos.ValidationIssue;
import com.vcrd.dto.ValidationDtos.ValidationResult;
import com.vcrd.entity.Invoice;
import com.vcrd.exception.NotFoundException;
import com.vcrd.repository.InvoiceDocumentRepository;
import com.vcrd.repository.InvoiceRepository;

/**
 * Runs the automatic steps of the lifecycle: document check, validation, Z PARK, goods receipt,
 * MIRO and clearing. Called right after a user action and by the scheduled job for retries.
 *
 * SAP writes follow an intent-then-call pattern so an invoice is never parked or posted twice:
 * 1. In its own transaction, mark the request as sent (the correlation ID was assigned when the
 *    user initiated the step). The @Version lock lets only one node win this write.
 * 2. Call SAP outside any transaction.
 * 3. Record the outcome. A timeout or any unknown error becomes *_UNCERTAIN, never a resend.
 * Uncertain invoices are reconciled by asking SAP for the correlation ID. Only once SAP confirms
 * it has no such document (after a grace period) does the invoice return for a safe retry.
 */
@Service
public class InvoiceProcessor {

    private static final Logger log = LoggerFactory.getLogger(InvoiceProcessor.class);
    private static final int MAX_STEPS = 10;

    /** Statuses the processor can advance without a user. */
    public static final Set<InvoiceStatus> AUTOMATIC = Set.of(
            InvoiceStatus.READY_FOR_VALIDATION,
            InvoiceStatus.ZPARK_IN_PROGRESS, InvoiceStatus.ZPARK_UNCERTAIN, InvoiceStatus.ZPARK_CREATED,
            InvoiceStatus.AWAITING_GR,
            InvoiceStatus.MIRO_IN_PROGRESS, InvoiceStatus.MIRO_UNCERTAIN,
            InvoiceStatus.AWAITING_CLEARING);

    private enum SapStep {
        ZPARK,
        MIRO
    }

    private final InvoiceRepository invoices;
    private final InvoiceDocumentRepository documents;
    private final WorkflowService workflow;
    private final InvoiceVerificationService verification;
    private final InvoiceValidationService validation;
    private final SapInvoiceGateway sap;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final Duration reconcileAfter;
    private final boolean asyncExtraction;
    /** Small fixed pool: OCR is CPU-heavy, and the background job retries anything not picked up here. */
    private final ExecutorService extractionExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "invoice-extraction");
        t.setDaemon(true);
        return t;
    });

    public InvoiceProcessor(InvoiceRepository invoices, InvoiceDocumentRepository documents, WorkflowService workflow,
            InvoiceVerificationService verification, InvoiceValidationService validation, SapInvoiceGateway sap,
            AuditService audit, PlatformTransactionManager txManager,
            @Value("${vcrd.sap.reconcile-after:PT5M}") Duration reconcileAfter,
            @Value("${vcrd.processing.async-extraction:true}") boolean asyncExtraction) {
        this.asyncExtraction = asyncExtraction;
        this.invoices = invoices;
        this.documents = documents;
        this.workflow = workflow;
        this.verification = verification;
        this.validation = validation;
        this.sap = sap;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
        this.reconcileAfter = reconcileAfter;
    }

    /** Advances the invoice as far as it can go automatically. Never throws; failures are retried later. */
    public void process(UUID invoiceId) {
        process(invoiceId, true);
    }

    /**
     * Called right after a user action. Fast steps (validation, SAP calls) run now so the response shows the
     * outcome; document extraction (OCR can take a minute for a scanned bundle) runs in the background when
     * async extraction is enabled, and the UI sees matchStatus change from PENDING when it is done.
     */
    public void processAfterUserAction(UUID invoiceId) {
        process(invoiceId, !asyncExtraction);
        if (asyncExtraction && needsExtraction(invoiceId)) {
            extractionExecutor.execute(() -> process(invoiceId, true));
        }
    }

    @PreDestroy
    void shutdown() {
        extractionExecutor.shutdown();
    }

    private void process(UUID invoiceId, boolean allowExtraction) {
        for (int i = 0; i < MAX_STEPS; i++) {
            try {
                if (!step(invoiceId, allowExtraction)) {
                    return;
                }
            } catch (RuntimeException e) {
                log.warn("Automatic step failed for invoice {}; will retry", invoiceId, e);
                return;
            }
        }
    }

    private boolean needsExtraction(UUID invoiceId) {
        Invoice invoice = tx.execute(s -> load(invoiceId));
        return invoice.getStatus() == InvoiceStatus.PENDING_VERIFICATION
                && invoice.getMatchStatus() == MatchStatus.PENDING;
    }

    /** Performs one step. Returns true if the status changed (so another step may follow). */
    boolean step(UUID invoiceId, boolean allowExtraction) {
        Invoice snapshot = tx.execute(s -> load(invoiceId));
        return switch (snapshot.getStatus()) {
            case PENDING_VERIFICATION -> {
                if (allowExtraction && snapshot.getMatchStatus() == MatchStatus.PENDING) {
                    tx.executeWithoutResult(s -> verification.verifyLatestAsSystem(load(invoiceId)));
                }
                yield false; // the user verifies next
            }
            case READY_FOR_VALIDATION -> validate(invoiceId);
            case ZPARK_IN_PROGRESS -> send(invoiceId, SapStep.ZPARK);
            case ZPARK_UNCERTAIN -> reconcile(invoiceId, SapStep.ZPARK);
            case ZPARK_CREATED, AWAITING_GR -> checkGoodsReceipt(invoiceId);
            case MIRO_IN_PROGRESS -> send(invoiceId, SapStep.MIRO);
            case MIRO_UNCERTAIN -> reconcile(invoiceId, SapStep.MIRO);
            case AWAITING_CLEARING -> checkClearing(invoiceId);
            default -> false;
        };
    }

    private boolean validate(UUID invoiceId) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            Invoice invoice = load(invoiceId);
            if (invoice.getStatus() != InvoiceStatus.READY_FOR_VALIDATION) {
                return false;
            }
            ValidationResult result = validation.validate(invoice);
            invoice.recordValidation(validation.toJson(result));
            if (result.passed()) {
                workflow.performSystem(invoiceId, WorkflowAction.PASS_VALIDATION, null);
            } else {
                String reasons = result.issues().stream().map(ValidationIssue::message)
                        .collect(Collectors.joining("; "));
                workflow.performSystem(invoiceId, WorkflowAction.FAIL_VALIDATION, reasons);
            }
            return true;
        }));
    }

    private boolean send(UUID invoiceId, SapStep step) {
        // 1. Record intent (own transaction, optimistic lock).
        Object request = tx.execute(s -> {
            Invoice invoice = load(invoiceId);
            InvoiceStatus expected = step == SapStep.ZPARK ? InvoiceStatus.ZPARK_IN_PROGRESS
                    : InvoiceStatus.MIRO_IN_PROGRESS;
            if (invoice.getStatus() != expected) {
                return null;
            }
            Instant requestedAt = step == SapStep.ZPARK ? invoice.getZparkRequestedAt() : invoice.getMiroRequestedAt();
            if (requestedAt != null) {
                // Sent before but the outcome was never recorded (e.g. crash): treat as unknown.
                workflow.performSystem(invoiceId, WorkflowAction.SAP_UNCERTAIN,
                        "Earlier " + step + " request has no recorded outcome; reconciling with SAP");
                return Boolean.TRUE;
            }
            Object built = step == SapStep.ZPARK ? buildParkRequest(invoice) : buildPostRequest(invoice);
            if (step == SapStep.ZPARK) {
                invoice.markZparkRequested();
            } else {
                invoice.markMiroRequested();
            }
            invoices.saveAndFlush(invoice);
            audit.recordSystem("INVOICE", invoiceId, "SAP_REQUEST_SENT",
                    Map.of("step", step.name(), "correlationId", correlationId(invoice, step)));
            return built;
        });
        if (request == null) {
            return false;
        }
        if (request == Boolean.TRUE) {
            return true;
        }

        // 2. Call SAP outside any transaction. 3. Record the outcome.
        try {
            SapDocument doc = step == SapStep.ZPARK ? sap.parkInvoice((ParkRequest) request)
                    : sap.postInvoice((PostRequest) request);
            recordSuccess(invoiceId, step, doc, WorkflowAction.SAP_CONFIRMED);
        } catch (SapRejectedException e) {
            tx.executeWithoutResult(s -> {
                load(invoiceId).recordSapMessages(String.join("\n", e.getMessages()));
                workflow.performSystem(invoiceId, WorkflowAction.SAP_REJECTED, "SAP rejected " + step + ": "
                        + e.getMessage());
            });
        } catch (RuntimeException e) {
            log.warn("{} outcome unknown for invoice {}", step, invoiceId, e);
            tx.executeWithoutResult(s -> {
                load(invoiceId).recordSapMessages(String.valueOf(e.getMessage()));
                workflow.performSystem(invoiceId, WorkflowAction.SAP_UNCERTAIN,
                        step + " outcome unknown (" + e.getMessage() + "); reconciling with SAP");
            });
        }
        return true;
    }

    private boolean reconcile(UUID invoiceId, SapStep step) {
        Invoice snapshot = tx.execute(s -> load(invoiceId));
        String correlationId = correlationId(snapshot, step);
        Instant requestedAt = step == SapStep.ZPARK ? snapshot.getZparkRequestedAt() : snapshot.getMiroRequestedAt();

        Optional<SapDocument> found = step == SapStep.ZPARK ? sap.findParkedByCorrelationId(correlationId)
                : sap.findPostedByCorrelationId(correlationId);
        if (found.isPresent()) {
            recordSuccess(invoiceId, step, found.get(), WorkflowAction.RECONCILED_FOUND);
            return true;
        }
        // SAP may still be processing a slow request: only conclude "not found" after a grace period.
        if (requestedAt != null && requestedAt.plus(reconcileAfter).isAfter(Instant.now())) {
            return false;
        }
        tx.executeWithoutResult(s -> workflow.performSystem(invoiceId, WorkflowAction.RECONCILED_NOT_FOUND,
                "SAP has no " + step + " document for reference " + correlationId + "; safe to retry"));
        return true;
    }

    private void recordSuccess(UUID invoiceId, SapStep step, SapDocument doc, WorkflowAction action) {
        tx.executeWithoutResult(s -> {
            Invoice invoice = load(invoiceId);
            if (step == SapStep.ZPARK) {
                invoice.recordParked(doc.docNo(), doc.fiscalYear(), doc.createdBySapId(), doc.createdByName(),
                        doc.createdAt());
            } else {
                invoice.recordPosted(doc.docNo(), doc.fiscalYear(), doc.amount());
            }
            invoice.recordSapMessages(doc.messages() == null ? null : String.join("\n", doc.messages()));
            workflow.performSystem(invoiceId, action, step + " document " + doc.docNo() + "/" + doc.fiscalYear());
        });
    }

    private boolean checkGoodsReceipt(UUID invoiceId) {
        record GrQuery(String poNumber, List<Integer> items) {
        }
        GrQuery query = tx.execute(s -> {
            Invoice invoice = load(invoiceId);
            return new GrQuery(invoice.getPurchaseOrder().getPoNumber(),
                    invoice.getLines().stream().map(l -> l.getPoLine().getItemNo()).distinct().toList());
        });
        GoodsReceipt gr = sap.goodsReceiptStatus(query.poNumber(), query.items());
        return Boolean.TRUE.equals(tx.execute(s -> {
            Invoice invoice = load(invoiceId);
            switch (gr.state()) {
                case RECEIVED -> {
                    invoice.recordGoodsReceipt(gr.materialDocNo());
                    workflow.performSystem(invoiceId, WorkflowAction.GR_CONFIRMED,
                            "Material document " + gr.materialDocNo());
                    return true;
                }
                case NOT_REQUIRED -> {
                    if (invoice.getStatus() != InvoiceStatus.ZPARK_CREATED) {
                        return false;
                    }
                    workflow.performSystem(invoiceId, WorkflowAction.GR_NOT_REQUIRED, null);
                    return true;
                }
                default -> {
                    if (invoice.getStatus() != InvoiceStatus.ZPARK_CREATED) {
                        return false; // still waiting
                    }
                    workflow.performSystem(invoiceId, WorkflowAction.GR_PENDING, null);
                    return true;
                }
            }
        }));
    }

    private boolean checkClearing(UUID invoiceId) {
        Invoice snapshot = tx.execute(s -> load(invoiceId));
        Optional<Clearing> clearing = sap.clearingStatus(snapshot.getMiroDocNo(), snapshot.getMiroFiscalYear());
        if (clearing.isEmpty()) {
            return false;
        }
        Clearing c = clearing.get();
        tx.executeWithoutResult(s -> {
            Invoice invoice = load(invoiceId);
            invoice.updateClearing(invoice.getMiroValue(), c.clearingDocNo(), c.clearingDate(), c.clearedAmount());
            workflow.performSystem(invoiceId, WorkflowAction.CLEARING_CONFIRMED,
                    "Clearing document " + c.clearingDocNo());
        });
        return true;
    }

    private ParkRequest buildParkRequest(Invoice i) {
        List<ParkLine> lines = i.getLines().stream()
                .map(l -> new ParkLine(l.getPoLine().getItemNo(), l.getQuantity(), l.getUnitPrice(), l.getAmount()))
                .toList();
        List<String> docRefs = documents.findByInvoiceIdOrderByVersionNo(i.getId()).stream()
                .map(d -> d.getId() + ":" + d.getSha256())
                .toList();
        return new ParkRequest(i.getZparkCorrelationId(), i.getTrackingNo(), i.getPurchaseOrder().getPoNumber(),
                i.getVendorCode(), i.getVendorInvoiceNo(), i.getInvoiceDate(), i.getCurrency(), i.getBaseAmount(),
                i.getCgstAmount(), i.getSgstAmount(), i.getIgstAmount(), i.getOtherTaxes(), i.getOtherCharges(),
                i.getTotalAmount(), lines, docRefs);
    }

    private PostRequest buildPostRequest(Invoice i) {
        return new PostRequest(i.getMiroCorrelationId(), i.getParkedDocNo(), i.getParkedFiscalYear());
    }

    private static String correlationId(Invoice invoice, SapStep step) {
        return step == SapStep.ZPARK ? invoice.getZparkCorrelationId() : invoice.getMiroCorrelationId();
    }

    private Invoice load(UUID invoiceId) {
        return invoices.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice " + invoiceId + " not found"));
    }
}

package com.vcrd.controller;

import com.vcrd.dto.InvoiceDtos;
import com.vcrd.constants.WorkflowAction;
import com.vcrd.entity.WorkflowHistory;
import com.vcrd.service.WorkflowService;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vcrd.dto.InvoiceDtos.InvoiceResponse;

import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/invoices/{id}")
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    public record ActionRequest(@Size(max = 2000) String remark) {
    }

    @PostMapping("/actions/{action}")
    public InvoiceResponse perform(@PathVariable UUID id, @PathVariable WorkflowAction action,
            @RequestBody(required = false) ActionRequest request) {
        return workflowService.perform(id, action, request == null ? null : request.remark());
    }

    @GetMapping("/actions")
    public List<WorkflowAction> availableActions(@PathVariable UUID id) {
        return workflowService.availableActions(id);
    }

    @GetMapping("/history")
    public List<WorkflowHistory> history(@PathVariable UUID id) {
        return workflowService.history(id);
    }
}

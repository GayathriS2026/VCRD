package com.vcrd;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcrd.config.DevDataSeeder;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vcrdtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "vcrd.storage.root=target/test-documents",
        "vcrd.jobs.enabled=false"
})
@AutoConfigureMockMvc
class InvoiceFlowTest {

    /** Valid against PO 4500012345 item 10 (rate 500): 10 x 500 = 5000 + 900 tax = 5900. */
    private static final String VALID = "\"baseAmount\":5000,\"taxAmount\":900,\"totalAmount\":5900,"
            + "\"lines\":[{\"poItemNo\":10,\"quantity\":10,\"unitPrice\":500}]";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void invoiceRunsThroughFullLifecycleToCleared() throws Exception {
        String site = login("site.user");
        String pmo = login("pmo.user");
        String approver = login("approver");

        mvc.perform(get("/api/v1/pos/4500012345").header("Authorization", site))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.paymentTermCode").value("Z314"));

        String id = createInvoice(site, "INV-T-001", VALID);

        // cannot submit without a document
        action(site, id, "SUBMIT")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOCUMENT_REQUIRED"));

        upload(site, id, "%PDF-1.4 test invoice 1");

        // submitting runs the document check automatically; this test file is not a readable PDF
        action(site, id, "SUBMIT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.matchStatus").value("NEEDS_REVIEW"));

        // an unreadable document needs an explicit manual confirmation
        action(site, id, "VERIFY")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REMARK_REQUIRED"));

        // verifying triggers validation against SAP PO data
        action(site, id, "VERIFY", "Checked against the original manually")
                .andExpect(jsonPath("$.status").value("VALIDATION_PASSED"));

        // system outcomes can never be set by users
        action(pmo, id, "PASS_VALIDATION").andExpect(status().isForbidden());
        action(site, id, "INITIATE_ZPARK").andExpect(status().isForbidden());

        // Z PARK -> SAP parks it -> goods receipt found -> waiting for approval
        action(pmo, id, "INITIATE_ZPARK")
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.parking.parkedDocNo").value(notNullValue()))
                .andExpect(jsonPath("$.sap.grMaterialDocNo").value("5000012345"));

        // the submitter cannot approve their own invoice; the approver can
        action(approver, id, "APPROVE").andExpect(jsonPath("$.status").value("APPROVED"));

        // MIRO -> posted -> cleared (the mock clears immediately)
        action(pmo, id, "POST_MIRO")
                .andExpect(jsonPath("$.status").value("CLEARED"))
                .andExpect(jsonPath("$.sap.miroDocNo").value(notNullValue()))
                .andExpect(jsonPath("$.clearing.clearingDocNo").value(notNullValue()));

        mvc.perform(get("/api/v1/invoices/" + id + "/history").header("Authorization", site))
                .andExpect(jsonPath("$[*].toStatus", hasItems("ZPARK_CREATED", "AWAITING_CLEARING", "CLEARED")));
    }

    @Test
    void validationFailureGoesToExceptionQueueWithExpectedVsActual() throws Exception {
        String site = login("site.user");
        // rate 600 exceeds the PO rate of 500
        String id = createInvoice(site, "INV-T-PRICE", "\"baseAmount\":6000,\"taxAmount\":0,\"totalAmount\":6000,"
                + "\"lines\":[{\"poItemNo\":10,\"quantity\":10,\"unitPrice\":600}]");
        submitAndVerify(site, id, "price test").andExpect(jsonPath("$.status").value("VALIDATION_FAILED"));

        mvc.perform(get("/api/v1/invoices/" + id + "/validation").header("Authorization", site))
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.issues[0].rule").value("UNIT_PRICE"))
                .andExpect(jsonPath("$.issues[0].expected").value("500.00"))
                .andExpect(jsonPath("$.issues[0].actual").value("600.00"));

        mvc.perform(get("/api/v1/invoices?status=VALIDATION_FAILED").header("Authorization", site))
                .andExpect(jsonPath("$.content[*].id", hasItems(id)));
    }

    @Test
    void lostSapResponseIsReconciledNotResent() throws Exception {
        String site = login("site.user");
        String id = createInvoice(site, "INV-SAP-TIMEOUT-1", VALID);
        submitAndVerify(site, id, "timeout test").andExpect(jsonPath("$.status").value("VALIDATION_PASSED"));

        // SAP parks the document but the response is lost: the invoice must be reconciled by reference
        action(login("pmo.user"), id, "INITIATE_ZPARK")
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.parking.parkedDocNo").value(notNullValue()));

        mvc.perform(get("/api/v1/invoices/" + id + "/history").header("Authorization", site))
                .andExpect(jsonPath("$[*].action", hasItems("SAP_UNCERTAIN", "RECONCILED_FOUND")));
    }

    @Test
    void sapRejectionReturnsInvoiceForCorrection() throws Exception {
        String site = login("site.user");
        String id = createInvoice(site, "INV-SAP-REJECT-1", VALID);
        submitAndVerify(site, id, "reject test");

        action(login("pmo.user"), id, "INITIATE_ZPARK")
                .andExpect(jsonPath("$.status").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.sap.messages").value(notNullValue()));
    }

    @Test
    void duplicateInvoiceNumberIsBlocked() throws Exception {
        String site = login("site.user");
        createInvoice(site, "INV-T-DUP", VALID);
        mvc.perform(post("/api/v1/invoices").header("Authorization", site)
                .contentType(MediaType.APPLICATION_JSON).content(invoiceJson("4500012345", "inv-t-dup", VALID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_INVOICE"));
    }

    @Test
    void unreleasedPoIsBlocked() throws Exception {
        mvc.perform(post("/api/v1/invoices").header("Authorization", login("site.user"))
                .contentType(MediaType.APPLICATION_JSON).content(invoiceJson("4500012347", "INV-T-NR", VALID)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PO_NOT_RELEASED"));
    }

    @Test
    void otherSiteCannotSeeInvoice() throws Exception {
        String id = createInvoice(login("site.user"), "INV-T-SCOPE", VALID);
        mvc.perform(get("/api/v1/invoices/" + id).header("Authorization", login("site2.user")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/pos/4500012345").header("Authorization", login("site2.user")))
                .andExpect(status().isForbidden());
    }

    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        mvc.perform(get("/api/v1/invoices")).andExpect(status().isUnauthorized());
    }

    private ResultActions submitAndVerify(String token, String id, String uniqueContent) throws Exception {
        upload(token, id, "%PDF-1.4 " + uniqueContent);
        action(token, id, "SUBMIT").andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));
        return action(token, id, "VERIFY", "Verified manually");
    }

    private ResultActions action(String token, String id, String action) throws Exception {
        return mvc.perform(post("/api/v1/invoices/" + id + "/actions/" + action).header("Authorization", token));
    }

    private ResultActions action(String token, String id, String action, String remark) throws Exception {
        return mvc.perform(post("/api/v1/invoices/" + id + "/actions/" + action).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("remark", remark))));
    }

    private String login(String username) throws Exception {
        String body = """
                {"username":"%s","password":"%s"}""".formatted(username, DevDataSeeder.DEMO_PASSWORD);
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(response).get("accessToken").asText();
    }

    private String createInvoice(String token, String invoiceNo, String amountsAndLines) throws Exception {
        String response = mvc.perform(post("/api/v1/invoices").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(invoiceJson("4500012345", invoiceNo, amountsAndLines)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(response);
        return node.get("id").asText();
    }

    private void upload(String token, String id, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "invoice.pdf", "application/pdf",
                content.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/invoices/" + id + "/documents").file(file).header("Authorization", token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1));
    }

    private static String invoiceJson(String po, String invoiceNo, String amountsAndLines) {
        return """
                {"poNumber":"%s","vendorInvoiceNo":"%s","invoiceDate":"2026-10-01",%s}"""
                .formatted(po, invoiceNo, amountsAndLines);
    }
}

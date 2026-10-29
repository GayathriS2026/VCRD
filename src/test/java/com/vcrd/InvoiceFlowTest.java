package com.vcrd;

import com.vcrd.entity.Invoice;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcrd.config.DevDataSeeder;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vcrdtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "vcrd.storage.root=target/test-documents"
})
@AutoConfigureMockMvc
class InvoiceFlowTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void siteUserSubmitsPmoForwardsApproverApproves() throws Exception {
        String site = login("site.user");
        String pmo = login("pmo.user");
        String approver = login("approver");

        mvc.perform(get("/api/v1/pos/4500012345").header("Authorization", site))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2));

        String id = createInvoice(site, "INV-T-001");

        // cannot submit without a document
        mvc.perform(post("/api/v1/invoices/" + id + "/actions/SUBMIT").header("Authorization", site))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOCUMENT_REQUIRED"));

        upload(site, id, "%PDF-1.4 test invoice 1");

        mvc.perform(post("/api/v1/invoices/" + id + "/actions/SUBMIT").header("Authorization", site))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // site user cannot do PMO's step
        mvc.perform(post("/api/v1/invoices/" + id + "/actions/SEND_FOR_APPROVAL").header("Authorization", site))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/invoices/" + id + "/actions/SEND_FOR_APPROVAL").header("Authorization", pmo))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        mvc.perform(post("/api/v1/invoices/" + id + "/actions/APPROVE").header("Authorization", approver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mvc.perform(get("/api/v1/invoices/" + id + "/history").header("Authorization", site))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void duplicateInvoiceNumberIsBlocked() throws Exception {
        String site = login("site.user");
        createInvoice(site, "INV-T-DUP");
        mvc.perform(post("/api/v1/invoices").header("Authorization", site)
                .contentType(MediaType.APPLICATION_JSON).content(invoiceJson("4500012345", "inv-t-dup")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_INVOICE"));
    }

    @Test
    void unreleasedPoIsBlocked() throws Exception {
        mvc.perform(post("/api/v1/invoices").header("Authorization", login("site.user"))
                .contentType(MediaType.APPLICATION_JSON).content(invoiceJson("4500012347", "INV-T-NR")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PO_NOT_RELEASED"));
    }

    @Test
    void otherSiteCannotSeeInvoice() throws Exception {
        String id = createInvoice(login("site.user"), "INV-T-SCOPE");
        mvc.perform(get("/api/v1/invoices/" + id).header("Authorization", login("site2.user")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/pos/4500012345").header("Authorization", login("site2.user")))
                .andExpect(status().isForbidden());
    }

    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        mvc.perform(get("/api/v1/invoices")).andExpect(status().isUnauthorized());
    }

    private String login(String username) throws Exception {
        String body = """
                {"username":"%s","password":"%s"}""".formatted(username, DevDataSeeder.DEMO_PASSWORD);
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(response).get("accessToken").asText();
    }

    private String createInvoice(String token, String invoiceNo) throws Exception {
        String response = mvc.perform(post("/api/v1/invoices").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(invoiceJson("4500012345", invoiceNo)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
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

    private static String invoiceJson(String po, String invoiceNo) {
        return """
                {"poNumber":"%s","vendorInvoiceNo":"%s","invoiceDate":"2026-10-01",
                 "baseAmount":50000,"taxAmount":9000,"totalAmount":59000,
                 "lines":[{"poItemNo":10,"quantity":100,"unitPrice":500}]}""".formatted(po, invoiceNo);
    }
}

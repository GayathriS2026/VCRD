package com.vcrd.identity;

/**
 * Proposed role catalog (BRD section 5). Not yet approved by the business; adjust once confirmed.
 */
public enum Role {
    SITE_USER,
    SITE_MANAGER,
    PMO,
    SCM,
    FINANCE,
    APPROVER,
    SAP_SUPPORT,
    PORTAL_ADMIN,
    AUDITOR;

    /** Roles that see invoices of every site. Everyone else is limited to their assigned sites. */
    public boolean isGlobal() {
        return this != SITE_USER && this != SITE_MANAGER;
    }
}

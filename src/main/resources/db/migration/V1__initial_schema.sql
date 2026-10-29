-- VCRD initial schema. Kept compatible with PostgreSQL and H2 (PostgreSQL mode).

-- ---------- Identity ----------
CREATE TABLE app_user (
    id              UUID PRIMARY KEY,
    username        VARCHAR(100) NOT NULL UNIQUE,
    password_hash   VARCHAR(100) NOT NULL,
    display_name    VARCHAR(200),
    email           VARCHAR(200),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE user_role (
    user_id UUID NOT NULL REFERENCES app_user (id),
    role    VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role)
);

-- Data scope: which sites a user may see. Global roles ignore this.
CREATE TABLE user_site (
    user_id   UUID NOT NULL REFERENCES app_user (id),
    site_code VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, site_code)
);

-- ---------- Purchase orders (read-only cache of SAP data) ----------
CREATE TABLE purchase_order (
    id             UUID PRIMARY KEY,
    po_number      VARCHAR(20) NOT NULL UNIQUE,
    vendor_code    VARCHAR(20) NOT NULL,
    vendor_name    VARCHAR(200),
    company_code   VARCHAR(10),
    site_code      VARCHAR(50) NOT NULL,
    currency       VARCHAR(3) NOT NULL,
    total_value    NUMERIC(18, 2),
    release_status VARCHAR(30) NOT NULL,
    last_synced_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE po_line (
    id            UUID PRIMARY KEY,
    po_id         UUID NOT NULL REFERENCES purchase_order (id),
    item_no       INTEGER NOT NULL,
    material_code VARCHAR(40),
    description   VARCHAR(200),
    ordered_qty   NUMERIC(18, 3) NOT NULL,
    uom           VARCHAR(10),
    unit_price    NUMERIC(18, 2) NOT NULL,
    CONSTRAINT uq_po_line UNIQUE (po_id, item_no)
);

-- ---------- Invoices ----------
CREATE SEQUENCE invoice_tracking_seq START WITH 1;

CREATE TABLE invoice (
    id                UUID PRIMARY KEY,
    tracking_no       VARCHAR(30) NOT NULL UNIQUE,
    po_id             UUID NOT NULL REFERENCES purchase_order (id),
    site_code         VARCHAR(50) NOT NULL,
    vendor_code       VARCHAR(20) NOT NULL,
    vendor_invoice_no VARCHAR(50) NOT NULL,
    invoice_date      DATE NOT NULL,
    currency          VARCHAR(3) NOT NULL,
    base_amount       NUMERIC(18, 2),
    tax_amount        NUMERIC(18, 2),
    total_amount      NUMERIC(18, 2),
    status            VARCHAR(40) NOT NULL,
    created_by        UUID NOT NULL REFERENCES app_user (id),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    version           BIGINT NOT NULL
);
CREATE INDEX ix_invoice_po ON invoice (po_id);
CREATE INDEX ix_invoice_status ON invoice (status);
CREATE INDEX ix_invoice_vendor_no ON invoice (vendor_code, vendor_invoice_no);

CREATE TABLE invoice_line (
    id         UUID PRIMARY KEY,
    invoice_id UUID NOT NULL REFERENCES invoice (id),
    po_line_id UUID NOT NULL REFERENCES po_line (id),
    quantity   NUMERIC(18, 3) NOT NULL,
    unit_price NUMERIC(18, 2) NOT NULL,
    amount     NUMERIC(18, 2) NOT NULL
);

CREATE TABLE invoice_document (
    id                UUID PRIMARY KEY,
    invoice_id        UUID NOT NULL REFERENCES invoice (id),
    version_no        INTEGER NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    size_bytes        BIGINT NOT NULL,
    sha256            VARCHAR(64) NOT NULL,
    storage_key       VARCHAR(500) NOT NULL,
    uploaded_by       UUID NOT NULL REFERENCES app_user (id),
    uploaded_at       TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_invoice_document_sha ON invoice_document (sha256);

-- ---------- Workflow ----------
CREATE TABLE workflow_history (
    id          UUID PRIMARY KEY,
    invoice_id  UUID NOT NULL REFERENCES invoice (id),
    from_status VARCHAR(40) NOT NULL,
    to_status   VARCHAR(40) NOT NULL,
    action      VARCHAR(40) NOT NULL,
    actor_id    UUID NOT NULL REFERENCES app_user (id),
    remark      VARCHAR(2000),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_workflow_history_invoice ON workflow_history (invoice_id);

-- ---------- Audit (append-only) ----------
CREATE TABLE audit_event (
    id             UUID PRIMARY KEY,
    entity_type    VARCHAR(50) NOT NULL,
    entity_id      UUID NOT NULL,
    action         VARCHAR(60) NOT NULL,
    actor_id       UUID,
    actor_username VARCHAR(100),
    actor_roles    VARCHAR(500),
    details        TEXT,
    correlation_id VARCHAR(64),
    occurred_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_audit_entity ON audit_event (entity_type, entity_id);

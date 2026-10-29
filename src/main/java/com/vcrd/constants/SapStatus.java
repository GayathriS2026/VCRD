package com.vcrd.constants;

/** Where the invoice is in SAP. Synced from SAP, never set by users. */
public enum SapStatus {
    UPLOADED,
    ZPARK,
    MIGO,
    MIRO,
    CLEARED,
    REJECTED
}

package com.vcrd.component;

import org.springframework.core.io.Resource;

/** File storage abstraction: local disk today, object storage (S3/MinIO/Azure Blob) later. */
public interface DocumentStorage {

    /** Stores the bytes and returns an opaque storage key. */
    String store(byte[] content, String extension);

    Resource load(String storageKey);
}

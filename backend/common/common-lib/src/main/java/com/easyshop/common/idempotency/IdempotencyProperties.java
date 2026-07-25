package com.easyshop.common.idempotency; // align with common-lib layout

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Global defaults. Namespace defaults to the service name so keys never collide
 * across services sharing one Redis (§4.9). maxCachedBodyBytes bounds the memory
 * an idempotency record can hold — important on the 8 GB box where Redis also
 * serves carts + cache (§7).
 */
@ConfigurationProperties(prefix = "easyshop.idempotency")
public class IdempotencyProperties {

    /** Header carrying the client-generated key. */
    private String keyHeader = "Idempotency-Key";

    /** Key namespace; defaults to spring.application.name via the auto-config. */
    private String namespace = "app";

    /** Largest request/response body the engine will buffer/replay. Default 256 KB. */
    private int maxCachedBodyBytes = 256 * 1024;

    public String getKeyHeader() { return keyHeader; }
    public void setKeyHeader(String keyHeader) { this.keyHeader = keyHeader; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public int getMaxCachedBodyBytes() { return maxCachedBodyBytes; }
    public void setMaxCachedBodyBytes(int v) { this.maxCachedBodyBytes = v; }
}
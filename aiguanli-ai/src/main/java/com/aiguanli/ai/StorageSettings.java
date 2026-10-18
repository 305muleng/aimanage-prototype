package com.aiguanli.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 远程存储尚未接入，默认不向本机写业务草稿或知识库索引。 */
@Component
public class StorageSettings {
    private final boolean localPersistenceEnabled;

    public StorageSettings(@Value("${app.storage.local-persistence-enabled:false}") boolean localPersistenceEnabled) {
        this.localPersistenceEnabled = localPersistenceEnabled;
    }

    public boolean localPersistenceEnabled() { return localPersistenceEnabled; }
}

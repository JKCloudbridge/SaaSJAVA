package app.platform.data.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring of the record store: its settings and the two helpers that hold a setting or a mapper (Sprint 14). */
@Configuration
@EnableConfigurationProperties({RecordProperties.class, SharingProperties.class, StorageProperties.class,
        SearchProperties.class,
        FileProperties.class, BulkProperties.class, ImportProperties.class, ListViewProperties.class,
        BeforeSaveProperties.class})
class DataModuleConfiguration {

    @Bean
    RecordJson recordJson() {
        return new RecordJson();
    }

    @Bean
    RecordValues recordValues(RecordJson json, RecordProperties properties) {
        return new RecordValues(json, properties.limits().maxRecordBytes());
    }
}

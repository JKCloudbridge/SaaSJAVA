package app.platform.data.internal;

import app.platform.sharedkernel.files.RecordFileDirectory;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers which files a record holds (SH5, ADR-0254). It is asked inside the transaction of the caller, under the
 * organization's tenant context, so row level security applies as for every other read of the files.
 */
@Component
class DefaultRecordFileDirectory implements RecordFileDirectory {

    private final FileStore files;

    DefaultRecordFileDirectory(FileStore files) {
        this.files = files;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> existing(UUID recordId, Collection<UUID> fileIds) {
        return files.activeAmong(recordId, fileIds);
    }
}

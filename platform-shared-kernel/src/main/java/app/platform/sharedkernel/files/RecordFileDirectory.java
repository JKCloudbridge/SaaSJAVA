package app.platform.sharedkernel.files;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Which files a record holds (SH5, ADR-0254). The data module implements it; a module that names files of a record in
 * its own documents (the cells of a fill-in) asks it, so that a cell can name only a file of its own header and never a
 * file of another record or one that is gone. A yes or no for each identifier: nothing about the file (its name, type,
 * size or bytes) is ever handed over.
 */
public interface RecordFileDirectory {

    /**
     * The identifiers, among those asked about, of files that are on the record now (uploaded and not removed).
     *
     * @param recordId the record
     * @param fileIds the identifiers to ask about
     * @return those that are active files of the record
     */
    Set<UUID> existing(UUID recordId, Collection<UUID> fileIds);
}

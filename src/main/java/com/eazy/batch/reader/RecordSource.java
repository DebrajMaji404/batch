package com.eazy.batch.reader;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

/**
 * A stream of flat records (field name -> text), one per row of a JSON or XML upload.
 * Implementations read the file incrementally, so a large upload never has to fit in memory.
 */
interface RecordSource extends Closeable {

    /**
     * One record. {@code error} is set when the record itself is unusable (nested value,
     * duplicate field) - the stream is still positioned after it, so reading can go on.
     * A value of {@code null} means the field was present but empty/null.
     */
    record Record(Map<String, String> values, String error) {
    }

    /**
     * @return the next record, or {@code null} at the end of the data
     * @throws IOException when the file is not well-formed; nothing after that point can be read
     */
    Record next() throws IOException;
}

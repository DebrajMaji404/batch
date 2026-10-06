package com.eazy.batch.report;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Job name -&gt; {@link ReportSpec}. Filled by generated {@code @BatchJob} configuration. */
public final class ReportSpecRegistry {

    private static final Map<String, ReportSpec> SPECS = new ConcurrentHashMap<>();

    private ReportSpecRegistry() {
    }

    public static void register(ReportSpec spec) {
        SPECS.put(spec.jobName(), spec);
    }

    /** All registered batch jobs, by name. */
    public static java.util.Collection<ReportSpec> all() {
        return java.util.List.copyOf(SPECS.values());
    }

    /** @return the spec, or {@code null} if the job was not generated from {@code @BatchJob} */
    public static ReportSpec find(String jobName) {
        return jobName == null ? null : SPECS.get(jobName);
    }
}

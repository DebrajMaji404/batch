package com.eazy.batch.constant;

/**
 * Application constants for batch processing
 */
public class AppConstant {

    public static class BatchJob {
        public static final int CHUNK_SIZE = 100;
        public static final int SKIP_LIMIT = 10;
        public static final int THREAD_POOL_SIZE = 5;
        public static final int QUEUE_CAPACITY = 100;

        private BatchJob() {
            // Private constructor to prevent instantiation
        }
    }
}
package com.carrotdata.cache.util;

import java.io.File;

public enum CacheConfigKey {
  CACHES_NAME_LIST_KEY("cache.names"),
  CACHES_TYPES_LIST_KEY("cache.types"),
  CACHE_VICTIM_NAME_KEY("victim.name"),
  CACHE_SNAPSHOT_NAME("cache.data"),
  ADMISSION_CONTROLLER_SNAPSHOT_NAME("ac.data"),
  THROUGHPUT_CONTROLLER_SNAPSHOT_NAME("tc.data"),
  RECYCLING_SELECTOR_SNAPSHOT_NAME("rc.data"),
  ADMISSION_QUEUE_SNAPSHOT_NAME("aq.data"),
  SCAVENGER_STATS_SNAPSHOT_NAME("scav.data"),
  CACHE_ENGINE_SNAPSHOT_NAME("engine.data"),
  DICTIONARY_DIR_NAME("dict"),
  DICTIONARY_FILE_EXT("dict"),
  CACHE_ROOT_DIR_PATH_KEY("root.dir.path"),
  CACHE_SEGMENT_SIZE_KEY("data.segment.size"),
  CACHE_MAXIMUM_SIZE_KEY("storage.size.max"),
  SCAVENGER_START_RUN_RATIO_KEY("scavenger.ratio.start"),
  SCAVENGER_STOP_RUN_RATIO_KEY("scavenger.ratio.stop"),
  SCAVENGER_DUMP_ENTRY_BELOW_MIN_KEY("scavenger.dump.entry.below.min"),
  SCAVENGER_DUMP_ENTRY_BELOW_MAX_KEY("scavenger.dump.entry.below.max"),
  SCAVENGER_DUMP_ENTRY_BELOW_STEP_KEY("scavenger.dump.entry.below.step"),
  SCAVENGER_NUMBER_THREADS_KEY("scavenger.number.threads"),
  CACHE_POPULARITY_NUMBER_RANKS_KEY("popularity.number.ranks"),
  CACHE_MINIMUM_ACTIVE_DATA_SET_RATIO_KEY("active.dataset.ratio.min"),
  CACHE_IO_STORAGE_POOL_SIZE_KEY("storage.io.pool.size"),
  SLRU_CACHE_INSERT_POINT_KEY("eviction.slru.insert.point"),
  SLRU_NUMBER_SEGMENTS_KEY("eviction.slru.number.segments"),
  ADMISSION_QUEUE_START_SIZE_RATIO_KEY("admission.queue.size.ratio.start"),
  ADMISSION_QUEUE_MIN_SIZE_RATIO_KEY("admission.queue.size.ratio.min"),
  ADMISSION_QUEUE_MAX_SIZE_RATIO_KEY("admission.queue.size.ratio.max"),
  PROMOTION_QUEUE_START_SIZE_RATIO_KEY("promotion.queue.size.ratio.start"),
  PROMOTION_QUEUE_MIN_SIZE_RATIO_KEY("promotion.queue.size.ratio.min"),
  PROMOTION_QUEUE_MAX_SIZE_RATIO_KEY("promotion.queue.size.ratio.max"),
  CACHE_RANDOM_PROMOTION_PROBABILITY_KEY("random.promotion.probability"),
  CACHE_WRITE_RATE_LIMIT_KEY("write.avg.rate.limit"),
  CACHE_VICTIM_PROMOTION_ON_HIT_KEY("victim.promotion.on.hit"),
  SPARSE_FILES_SUPPORT_KEY("sparse.files.support"),
  START_INDEX_NUMBER_OF_SLOTS_POWER_KEY("index.slots.power"),
  THROUGHPUT_CHECK_INTERVAL_SEC_KEY("throughput.check.interval.sec"),
  SCAVENGER_RUN_INTERVAL_SEC_KEY("scavenger.run.interval.sec"),
  THROUGHPUT_CONTROLLER_TOLERANCE_LIMIT_KEY("throughput.tolerance.limit"),
  THROUGHPUT_CONTROLLER_ADJUSTMENT_STEPS_KEY("throughput.adjustment.steps"),
  INDEX_DATA_EMBEDDED_KEY("index.data.embedded"),
  INDEX_DATA_EMBEDDED_SIZE_KEY("index.data.embedded.size.max"),
  INDEX_FORMAT_MAIN_QUEUE_IMPL_KEY("index.format.impl"),
  INDEX_FORMAT_ADMISSION_QUEUE_IMPL_KEY("index.format.aq.impl"),
  CACHE_EVICTION_POLICY_IMPL_KEY("eviction.policy.impl"),
  CACHE_ADMISSION_CONTROLLER_IMPL_KEY("admission.controller.impl"),
  CACHE_PROMOTION_CONTROLLER_IMPL_KEY("promotion.controller.impl"),
  CACHE_THROUGHPUT_CONTROLLER_IMPL_KEY("throughput.controller.impl"),
  CACHE_RECYCLING_SELECTOR_IMPL_KEY("recycling.selector.impl"),
  CACHE_DATA_WRITER_IMPL_KEY("data.writer.impl"),
  CACHE_MEMORY_DATA_READER_IMPL_KEY("memory.data.reader.impl"),
  CACHE_FILE_DATA_READER_IMPL_KEY("file.data.reader.impl"),
  CACHE_BLOCK_WRITER_BLOCK_SIZE_KEY("block.writer.block.size"),
  FILE_PREFETCH_BUFFER_SIZE_KEY("file.prefetch.buffer.size"),
  CACHE_EXPIRE_SUPPORT_IMPL_KEY("expire.support.impl"),
  CACHE_RANDOM_ADMISSION_RATIO_START_KEY("random.admission.ratio.start"),
  CACHE_RANDOM_ADMISSION_RATIO_STOP_KEY("random.admission.ratio.stop"),
  CACHE_EXPIRATION_BIN_START_VALUE_KEY("expire.bin.value.start"),
  CACHE_EXPIRATION_MULTIPLIER_VALUE_KEY("expire.multiplier.value"),
  CACHE_EVICTION_DISABLED_MODE_KEY("eviction.disabled"),
  CACHE_ROLLING_WINDOW_COUNTER_BINS_KEY("rwc.bins"),
  CACHE_ROLLING_WINDOW_COUNTER_DURATION_KEY("rwc.window"),
  CACHE_HYBRID_INVERSE_MODE_KEY("hybrid.inverse.mode"),
  CACHE_VICTIM_PROMOTION_THRESHOLD_KEY("victim.promotion.threshold"),
  CACHE_SPIN_WAIT_TIME_NS_KEY("spin.wait.time.ns"),
  CACHE_JMX_METRICS_DOMAIN_NAME_KEY("jmx.metrics.domain.name"),
  CACHE_STREAMING_SUPPORT_BUFFER_SIZE_KEY("streaming.buffer.size"),
  CACHE_MAX_WAIT_ON_PUT_MS_KEY("cache.wait.put.max.ms"),
  CACHE_MAX_KEY_VALUE_SIZE_KEY("cache.max.kv.size"),
  OBJECT_CACHE_INITIAL_BUFFER_SIZE_KEY("objectcache.buffer.size.start"),
  OBJECT_CACHE_MAX_BUFFER_SIZE_KEY("objectcache.buffer.size.max"),
  CACHE_TLS_SUPPORTED_KEY("tls.supported"),
  CACHE_TLS_INITIAL_BUFFER_SIZE_KEY("tls.buffer.size.start"),
  CACHE_TLS_MAXIMUM_BUFFER_SIZE_KEY("tls.buffer.size.max"),
  CACHE_COMPRESSION_ENABLED_KEY("compression.enabled"),
  CACHE_COMPRESSION_BLOCK_SIZE_KEY("compression.block.size"),
  CACHE_COMPRESSION_DICTIONARY_SIZE_KEY("compression.dictionary.size"),
  CACHE_COMPRESSION_LEVEL_KEY("compression.level"),
  CACHE_COMPRESSION_CODEC_KEY("compression.codec"),
  CACHE_COMPRESSION_DICTIONARY_ENABLED_KEY("compression.dictionary.enabled"),
  CACHE_COMPRESSION_KEYS_ENABLED_KEY("compression.keys.enabled"),
  CACHE_COMPRESSION_DICTIONARY_TRAINING_ASYNC_KEY("compression.dictionary.training.async"),
  CACHE_SAVE_ON_SHUTDOWN_KEY("save.on.shutdown"),
  CACHE_ESTIMATED_AVG_KV_SIZE_KEY("estimated.avg.kv.size"),
  CACHE_MEMORY_BUFFER_POOL_MAX_SIZE_KEY("memory.buffer.pool.size.max"),
  CACHE_PROACTIVE_EXPIRATION_FACTOR_KEY("proactive.expiration.factor"),
  VACUUM_CLEANER_INTERVAL_SEC_KEY("vacuum.cleaner.interval"),
  DEFAULT_CACHES_NAME_LIST("cache"), // only one cache
  DEFAULT_CACHES_TYPES_LIST("memory"),
  DEFAULT_CACHE_CONFIG_FILE_NAME("cache.conf"),
  DEFAULT_CACHE_CONFIG_DIR_NAME("conf"),
  DEFAULT_CACHE_ROOT_DIR_PATH("." + File.separator + "data");


  private final String key;

  CacheConfigKey(String key) {
    this.key = key;
  }

  public String getKey() {
    return key;
  }
}

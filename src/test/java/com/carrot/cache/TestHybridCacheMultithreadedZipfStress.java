/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.carrot.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import com.carrot.cache.Cache.Builder;
import com.carrot.cache.controllers.AQBasedAdmissionController;
import com.carrot.cache.controllers.AdmissionController;
import com.carrot.cache.controllers.BaseAdmissionController;
import com.carrot.cache.controllers.LRCRecyclingSelector;
import com.carrot.cache.controllers.MinAliveRecyclingSelector;
import com.carrot.cache.controllers.RecyclingSelector;
import com.carrot.cache.eviction.EvictionPolicy;
import com.carrot.cache.eviction.FIFOEvictionPolicy;
import com.carrot.cache.eviction.SLRUEvictionPolicy;
import com.carrot.cache.util.Epoch;
import com.carrot.cache.util.TestUtils;

public class TestHybridCacheMultithreadedZipfStress extends TestCacheMultithreadedZipfBase {

  int victim_segmentSize = 16 * 1024 * 1024;

  long victim_maxCacheSize = 1000L * victim_segmentSize;

  double victim_minActiveRatio = 0.5;

  int victim_scavengerInterval = 10; // seconds

  double victim_scavDumpBelowRatio = 0.5;

  boolean victim_promoteOnHit = true;

  double victim_promoteThreshold = 0.9;

  boolean hybridCacheInverseMode = false;

  protected Class<? extends EvictionPolicy> victim_epClz = FIFOEvictionPolicy.class;

  protected Class<? extends RecyclingSelector> victim_rsClz = LRCRecyclingSelector.class;

  protected Class<? extends AdmissionController> victim_acClz = BaseAdmissionController.class;

  @Before
  public void setUp() {
    // Parent cache
    this.offheap = true;
    this.numRecords = 1000000;
    this.numIterations = 100 * this.numRecords;
    this.numThreads = 4;
    this.minActiveRatio = 0.9;
    this.segmentSize = 4 * 1024 * 1024;
    this.maxCacheSize = 100L * this.segmentSize; // 16 GB in RAM
    this.epClz = SLRUEvictionPolicy.class;
    this.rsClz = MinAliveRecyclingSelector.class;
    //this.acClz = AQBasedAdmissionController.class;
    this.aqStartRatio = 1.0;
    this.scavengerInterval = 4; // scavenger interval in sec
    this.hybridCacheInverseMode = false;
    this.scavDumpBelowRatio = 0.1;

    // victim cache
    this.victim_segmentSize = 12 * 1024 * 1024;
    this.victim_maxCacheSize = 100L * this.victim_segmentSize; // 160GB
    this.victim_minActiveRatio = 0.0;
    this.victim_scavDumpBelowRatio = 0.5;
    this.victim_scavengerInterval = 1000;
    this.victim_promoteOnHit = false;
    this.victim_promoteThreshold = 0.9;
    this.victim_epClz = SLRUEvictionPolicy.class;
    this.victim_rsClz = MinAliveRecyclingSelector.class;
    this.zipfAlpha = 0.9;

    Epoch.reset();
    
  }

  @After
  public void tearDown() throws IOException {
    Cache victim = cache.getVictimCache();
    System.out.printf("main cache: size=%d hit rate=%f items=%d\n", cache.getStorageAllocated(),
      cache.getHitRate(), cache.size());

    if (victim != null) {
      System.out.printf("victim cache: size=%d hit rate=%f items=%d\n", victim.getStorageAllocated(),
        victim.getHitRate(), victim.size());
    }
    super.tearDown();
    // Delete temp data
    if (victim != null) {
      TestUtils.deleteCacheFiles(victim);
    }
  }

  @Override
  protected Builder withAddedConfigurations(Builder b) {
    b.withCacheHybridInverseMode(hybridCacheInverseMode)
    .withCacheSpinWaitTimeOnHighPressure(0)
    .withSLRUInsertionPoint(7);
    return b;
  }

  protected Cache createCache() throws IOException {
    Cache parent = super.createCache();

    String cacheName = this.victimCacheName;
    // Data directory
    Path victim_rootDirPath = Files.createTempDirectory(null);
    String rootDir = victim_rootDirPath.toFile().getAbsolutePath();

    Cache.Builder builder = new Cache.Builder(cacheName);

    builder.withCacheDataSegmentSize(victim_segmentSize).withCacheMaximumSize(victim_maxCacheSize)
        .withScavengerRunInterval(victim_scavengerInterval)
        .withScavengerDumpEntryBelowStart(victim_scavDumpBelowRatio)
        .withCacheEvictionPolicy(victim_epClz.getName())
        .withRecyclingSelector(victim_rsClz.getName())
        .withCacheRootDir(rootDir)
        .withMinimumActiveDatasetRatio(victim_minActiveRatio)
        .withVictimCachePromoteOnHit(victim_promoteOnHit)
        .withVictimCachePromotionThreshold(victim_promoteThreshold)
        .withAdmissionController(victim_acClz.getName())
        .withCacheSpinWaitTimeOnHighPressure(0)
        .withAdmissionQueueStartSizeRatio(aqStartRatio);
    Cache victim = builder.buildDiskCache();
    parent.setVictimCache(victim);
    parent.registerJMXMetricsSink();
    
    return parent;
  }

  @Test
  public void testLRUEvictionAndMinAliveSelectorBytesAPI() throws IOException {
    System.out.println("Bytes API: eviction=LRU, selector=MinAlive");
    this.parentCacheName = "RAM-AC-OFF";
    this.victimCacheName = "DISK-AC-OFF";
    super.testContinuosLoadBytesRun();
  }

  @Ignore
  @Test
  public void testLRUEvictionAndMinAliveSelectorWithAQBytesAPI() throws IOException {
    System.out.println("Bytes API: eviction=LRU, selector=MinAlive - AQ");
    this.parentCacheName = "RAM-AC-ON";
    this.victimCacheName = "DISK-AC-ON";
    this.victim_acClz = AQBasedAdmissionController.class;
    super.testContinuosLoadBytesRun();
  }
}

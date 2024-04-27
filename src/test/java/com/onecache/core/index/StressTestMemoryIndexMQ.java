/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.onecache.core.index;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.onecache.core.index.MemoryIndex.Type;

public abstract class StressTestMemoryIndexMQ extends TestMemoryIndexFormatBase{
  private static final Logger LOG = LoggerFactory.getLogger(StressTestMemoryIndexMQ.class);

  @Before
  public void setUp() {
  }
  
  @After
  public void tearDown() {
    //
  }
  
  private void setup() {
    super.setUp();
  }
  
  private void teardown() {
    super.tearDown();
  }
  
  @Test
  public void stressTest() {
    for (int i = 0; i < 100; i++) {
      LOG.info("\nRun #{}", i);
      setup();
      testLoadReadNoRehashBytes();
      teardown();
      
      setup();
      testLoadReadNoRehashMemory();
      teardown();
      
      setup();
      testLoadReadNoRehashBytesWithHit();
      teardown();
      
      setup();
      testLoadReadNoRehashMemoryWithHit();
      teardown();
      
      setup();
      testLoadReadDeleteNoRehashBytes();
      teardown();
      
      setup();
      testLoadReadDeleteNoRehashMemory();
      teardown();
      
      setup();
      testLoadReadWithRehashBytes();
      teardown();
      
      setup();
      testLoadReadWithRehashMemory();
      teardown();
      
      setup();
      testLoadReadWithRehashBytesWithHit();
      teardown();
      
      setup();
      testLoadReadWithRehashMemoryWithHit();
      teardown();
      
      setup();
      testLoadReadDeleteWithRehashBytes();
      teardown();
      
      setup();
      testLoadReadDeleteWithRehashMemory();
      teardown();
      
//      setup();
//      testEvictionBytes();
//      teardown();
//      
//      setup();
//      testEvictionMemory();
//      teardown();
      
    }
  }

  @Override
  protected MemoryIndex getMemoryIndex() {
    return new MemoryIndex("default", Type.MQ);
  }
}

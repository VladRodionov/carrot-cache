/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carrotdata.cache;

import java.io.IOException;
import java.util.Random;

import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestFileCacheGetRangeAPI extends TestMemoryCacheGetRangeAPI {
  private static final Logger LOG = LoggerFactory.getLogger(TestFileCacheGetRangeAPI.class);

  @Before
  public void setUp() throws IOException {
    this.memory = false;
    cache = createCache();
    this.numRecords = 100000;
    this.r = new Random();
    long seed = System.currentTimeMillis();
    LOG.info("r.seed={}", seed);
  }

}

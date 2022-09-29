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
package com.carrot.cache.io;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.carrot.cache.util.CarrotConfig;
import com.carrot.cache.util.UnsafeAccess;

import static com.carrot.cache.io.BlockReaderWriterSupport.*;
import static com.carrot.cache.util.Utils.getItemSize;

public class BlockMemoryDataReader implements DataReader {

  private int blockSize;

  public BlockMemoryDataReader() {
  }

  @Override
  public void init(String cacheName) {
    this.blockSize = CarrotConfig.getInstance().getBlockWriterBlockSize(cacheName);
  }

  @Override
  public int read(IOEngine engine, byte[] key, int keyOffset, int keySize, int sid, long offset,
      int size, /* can be unknown -1 */
      byte[] buffer, int bufOffset) {

    int avail = buffer.length - bufOffset;
    // sanity check
    if (size > avail) {
      return size;
    }
    // Segment read lock is already held by this thread
    Segment s = engine.getSegmentById(sid);
    if (s == null) {
      // TODO: error
      return IOEngine.NOT_FOUND;
    }

    if (!s.isOffheap()) {
      return IOEngine.NOT_FOUND;
    }
    long dataSize = getFullDataSize(s, blockSize);
    if (size > 0 && dataSize < offset + size) {
      // Rare situation - wrong segment - hash collision
      return IOEngine.NOT_FOUND;
    }
    long ptr = s.getAddress() + offset;
    ptr = findInBlock(ptr, key, keyOffset, keySize);
    if (ptr < 0) {
      return IOEngine.NOT_FOUND;
    } else {
      int requiredSize = getItemSize(ptr);
      if (requiredSize > avail) {
        return requiredSize;
      }
      UnsafeAccess.copy(ptr, buffer, bufOffset, requiredSize);
      return requiredSize;
    }
  }

  @Override
  public int read(IOEngine engine, byte[] key, int keyOffset, int keySize, int sid, long offset,
      int size, ByteBuffer buffer) {
    // Segment read lock is already held by this thread
    int avail = buffer.remaining();
    // Sanity check
    if (size > avail) {
      return size;
    }
    // Segment read lock is already held by this thread
    Segment s = engine.getSegmentById(sid);
    if (s == null) {
      // TODO: error
      return IOEngine.NOT_FOUND;
    }

    if (!s.isOffheap()) {
      return IOEngine.NOT_FOUND;
    }
    long dataSize = getFullDataSize(s, blockSize);
    if (size > 0 && dataSize < offset + size) {
      // Rare situation - wrong segment - hash collision
      return IOEngine.NOT_FOUND;
    }
    long ptr = s.getAddress() + offset;
    ptr = findInBlock(ptr, key, keyOffset, keySize);
    if (ptr < 0) {
      return IOEngine.NOT_FOUND;
    } else {
      int requiredSize = getItemSize(ptr);
      if (requiredSize > avail) {
        return requiredSize;
      }
      int pos = buffer.position();
      UnsafeAccess.copy(ptr, buffer, requiredSize);
      buffer.position(pos);
      return requiredSize;
    }
  }

  @Override
  public int read(IOEngine engine, long keyPtr, int keySize, int sid, long offset, int size,
      byte[] buffer, int bufOffset) {
    // Segment read lock is already held by this thread
    int avail = buffer.length - bufOffset;
    // Sanity check
    if (size > avail) {
      return size;
    }
    // Segment read lock is already held by this thread
    Segment s = engine.getSegmentById(sid);
    if (s == null) {
      // TODO: error
      return IOEngine.NOT_FOUND;
    }

    if (!s.isOffheap()) {
      return IOEngine.NOT_FOUND;
    }
    long dataSize = getFullDataSize(s, blockSize);
    if (size > 0 && dataSize < offset + size) {
      // Rare situation - wrong segment - hash collision
      return IOEngine.NOT_FOUND;
    }
    long ptr = s.getAddress() + offset;
    ptr = findInBlock(ptr, keyPtr, keySize);
    if (ptr < 0) {
      return IOEngine.NOT_FOUND;
    } else {
      int requiredSize = getItemSize(ptr);
      if (requiredSize > avail) {
        return requiredSize;
      }
      UnsafeAccess.copy(ptr, buffer, bufOffset, requiredSize);
      return requiredSize;
    }
  }

  @Override
  public int read(IOEngine engine, long keyPtr, int keySize, int sid, long offset, int size,
      ByteBuffer buffer) {
    // Segment read lock is already held by this thread
    int avail = buffer.remaining();
    // Sanity check
    if (size > avail) {
      return size;
    }
    // Segment read lock is already held by this thread
    Segment s = engine.getSegmentById(sid);
    if (s == null) {
      // TODO: error
      return IOEngine.NOT_FOUND;
    }

    if (!s.isOffheap()) {
      return IOEngine.NOT_FOUND;
    }
    long dataSize = getFullDataSize(s, blockSize);
    if (size > 0 && dataSize < offset + size) {
      // Rare situation - wrong segment - hash collision
      return IOEngine.NOT_FOUND;
    }
    long ptr = s.getAddress() + offset;
    ptr = findInBlock(ptr, keyPtr, keySize);
    if (ptr < 0) {
      return IOEngine.NOT_FOUND;
    } else {
      int requiredSize = getItemSize(ptr);
      if (requiredSize > avail) {
        return requiredSize;
      }
      int pos = buffer.position();
      UnsafeAccess.copy(ptr, buffer, requiredSize);
      buffer.position(pos);
      return requiredSize;
    }
  }

  @Override
  public SegmentScanner getSegmentScanner(IOEngine engine, Segment s) throws IOException {
    CarrotConfig config = CarrotConfig.getInstance();
    String cacheName = engine.getCacheName();
    int blockSize = config.getBlockWriterBlockSize(cacheName);
    return new BlockMemorySegmentScanner(s, blockSize);
  }
}

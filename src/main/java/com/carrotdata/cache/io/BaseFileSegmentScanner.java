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
package com.carrotdata.cache.io;

import static com.carrotdata.cache.io.BlockReaderWriterSupport.OPT_META_SIZE;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.carrotdata.cache.util.UnsafeAccess;
import com.carrotdata.cache.util.Utils;

/**
 * Segment scanner Usage: while(scanner.hasNext()){ // do job // ... // next() scanner.next(); }
 */
public final class BaseFileSegmentScanner implements SegmentScanner {
  private static final Logger LOG =
      LoggerFactory.getLogger(BaseFileSegmentScanner.class);

  /*
   * Data segment
   */
  Segment segment;
  /*
   * Prefetch buffer
   */
  PrefetchBuffer prefetch;
  /*
   * Current scanner index
   */
  int currentIndex = 0;
  /**
   * Current offset in the current block
   */
  int blockOffset = 0;
  /**
   * Current block size
   */
  int blockSize = 4096;
  /**
   * Internal buffer
   */
  byte[] buf;

  /*
   * Private constructor
   */
  BaseFileSegmentScanner(Segment s, RandomAccessFile file, int bufSize)
      throws IOException {
    // Make sure it is sealed
    if (s.isSealed() == false) {
      throw new RuntimeException("segment is not sealed");
    }
    this.segment = s;
    s.readLock();
    // Allocate internal buffer
    int bufferSize = 1 << 16;
    buf = new byte[bufferSize];
    this.prefetch = new PrefetchBuffer(file, bufSize);
    nextBlock();
  }
  private void checkBuffer(int requiredSize) {
    if (requiredSize <= buf.length) {
      return;
    }
    this.buf = new byte[requiredSize];
  }
  private void nextBlock() throws IOException {
    if (currentIndex >= segment.getTotalItems()) {
      return;
    }
    byte[] buffer = prefetch.getBuffer();
    int bufferOffset = prefetch.getBufferOffset();
    // next blockSize
    if (this.prefetch.available() <= OPT_META_SIZE) {
      this.prefetch.prefetch();
      bufferOffset = 0;
    }
    this.blockSize = UnsafeAccess.toInt(buffer, bufferOffset);
    int id = UnsafeAccess.toInt(buffer, bufferOffset + Utils.SIZEOF_INT);
    int size2 = UnsafeAccess.toInt(buffer, bufferOffset + 2 * Utils.SIZEOF_INT);
    if (this.prefetch.available() < this.blockSize + OPT_META_SIZE) {
      this.prefetch.prefetch();
      bufferOffset = 0;
    }
    checkBuffer(this.blockSize);
    if (id != -1 || this.blockSize != size2 || this.blockSize < 0) {
      // PANIC - memory corruption
      LOG.error(
        "Segment size={} offset={} size1={} size2={} id={} index={} total items={}",
        segment.getSegmentDataSize(), this.prefetch.getFileOffset(), this.blockSize, size2,
        id, currentIndex, segment.getTotalItems());
      throw new RuntimeException();
    } else {
      UnsafeAccess.copy(buffer, bufferOffset + OPT_META_SIZE, this.buf, 0, this.blockSize);
    }
    // Advance segment offset
    this.prefetch.advance(this.blockSize + OPT_META_SIZE);
    this.blockOffset = 0;
  }

  public boolean hasNext() {
    return currentIndex < segment.getTotalItems();
  }

  public boolean next() throws IOException {

    int keySize = Utils.readUVInt(buf, this.blockOffset);
    int keySizeSize = Utils.sizeUVInt(keySize);
    this.blockOffset += keySizeSize;
    int valueSize = Utils.readUVInt(buf, this.blockOffset);
    int valueSizeSize = Utils.sizeUVInt(valueSize);
    this.blockOffset += valueSizeSize;
    this.blockOffset += keySize + valueSize;
    this.currentIndex++;
    if (this.blockOffset >= this.blockSize) {
      nextBlock();
    }
    return true;
  }

  /**
   * Get expiration time of a current cached entry
   * @return expiration time
   * @deprecated use memory index to retrieve expiration time
   */
  public final long getExpire() {
    return -1;
  }

  /**
   * Get key size of a current cached entry
   * @return key size
   */
  public final int keyLength() {
    return Utils.readUVInt(this.buf, this.blockOffset);
  }

  /**
   * Get current value size
   * @return value size
   */

  public final int valueLength() {
    int off = this.blockOffset;
    int keySize = Utils.readUVInt(this.buf, off);
    int keySizeSize = Utils.sizeUVInt(keySize);
    off += keySizeSize;
    return Utils.readUVInt(this.buf, off);
  }

  /**
   * Get current key's address
   * @return keys address or 0 (if not supported)
   */
  public final long keyAddress() {
    return 0;
  }

  private final int keyOffset() {
    int off = this.blockOffset;
    int keySize = Utils.readUVInt(this.buf, off);
    int keySizeSize = Utils.sizeUVInt(keySize);
    off += keySizeSize;
    int valueSize = Utils.readUVInt(this.buf, off);
    int valueSizeSize = Utils.sizeUVInt(valueSize);
    off += valueSizeSize;
    return off;
  }

  /**
   * Get current value's address
   * @return values address
   */
  public final long valueAddress() {
    return 0;
  }

  private final int valueOffset() {
    int off = this.blockOffset;
    int keySize = Utils.readUVInt(this.buf, off);
    int keySizeSize = Utils.sizeUVInt(keySize);
    off += keySizeSize;
    int valueSize = Utils.readUVInt(this.buf, off);
    int valueSizeSize = Utils.sizeUVInt(valueSize);
    off += valueSizeSize + keySize;
    return off;
  }

  @Override
  public void close() throws IOException {
    segment.readUnlock();
  }

  @Override
  public int getKey(ByteBuffer b) {
    int keySize = keyLength();
    int keyOffset = keyOffset();
    if (keySize <= b.remaining()) {
      b.put(this.buf, keyOffset, keySize);
    }
    return keySize;
  }

  @Override
  public int getValue(ByteBuffer b) {
    int valueSize = valueLength();
    int valueOffset = valueOffset();
    if (valueSize <= b.remaining()) {
      b.put(this.buf, valueOffset, valueSize);
    }
    return valueSize;
  }

  @Override
  public int getKey(byte[] buffer, int offset) throws IOException {
    int keySize = keyLength();
    if (keySize > buffer.length - offset) {
      return keySize;
    }
    int keyOffset = keyOffset();
    System.arraycopy(this.buf, keyOffset, buffer, offset, keySize);
    return keySize;
  }

  @Override
  public int getValue(byte[] buffer, int offset) throws IOException {
    int valueSize = valueLength();
    if (valueSize > buffer.length - offset) {
      return valueSize;
    }
    int valueOffset = valueOffset();
    System.arraycopy(this.buf, valueOffset, buffer, offset, valueSize);
    return valueSize;
  }

  @Override
  public Segment getSegment() {
    return this.segment;
  }

  @Override
  public long getOffset() {
    return this.prefetch.getFileOffset();
  }

  @Override
  public boolean isDirect() {
    return false;
  }
}

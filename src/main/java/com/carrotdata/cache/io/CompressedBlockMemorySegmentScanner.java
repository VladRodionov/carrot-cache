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

import static com.carrotdata.cache.compression.CompressionCodec.COMP_META_SIZE;
import static com.carrotdata.cache.compression.CompressionCodec.COMP_SIZE_OFFSET;
import static com.carrotdata.cache.compression.CompressionCodec.DICT_VER_OFFSET;
import static com.carrotdata.cache.compression.CompressionCodec.SIZE_OFFSET;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.carrotdata.cache.compression.CompressionCodec;
import com.carrotdata.cache.util.UnsafeAccess;
import com.carrotdata.cache.util.Utils;

/**
 * Segment scanner Usage: while(scanner.hasNext()){ // do job // ... // next() scanner.next(); }
 */
public final class CompressedBlockMemorySegmentScanner implements SegmentScanner {
  private static final Logger LOG =
      LoggerFactory.getLogger(CompressedBlockMemorySegmentScanner.class);

  /*
   * Data segment
   */
  Segment segment;
  /*
   * Current scanner index
   */
  int currentIndex = 0;

  /**
   * Current offset in a parent segment
   */
  int offset = 0;

  /**
   * Current offset in the current block
   */
  int blockOffset = 0;

  /**
   * Current block size (decompressed)
   */
  int blockSize = 0;

  /**
   * Internal buffer address
   */
  long bufPtr;

  /**
   * Internal buffer size
   */
  int bufferSize;

  /**
   * Compression codec
   */
  private CompressionCodec codec;
  
  private long segmentSize ;

  /*
   * Private constructor
   */
  CompressedBlockMemorySegmentScanner(Segment s, CompressionCodec codec) {
    // Make sure it is sealed
    if (s.isSealed() == false) {
      throw new RuntimeException("segment is not sealed");
    }
    this.segment = s;
    this.segmentSize = s.getSegmentDataSize();
    s.readLock();
    // Allocate internal buffer
    this.bufferSize = 1 << 16;
    this.bufPtr = UnsafeAccess.malloc(this.bufferSize);
    this.codec = codec;
    nextBlock();

  }

  private void checkBuffer(int requiredSize) {
    if (requiredSize <= this.bufferSize) {
      return;
    }
    UnsafeAccess.free(this.bufPtr);
    this.bufferSize = requiredSize;
    this.bufPtr = UnsafeAccess.malloc(this.bufferSize);
  }

  private boolean nextBlock() {
    //if (this.currentIndex >= segment.getTotalItems()) {
    //  return;
    //}
    if (this.offset >= this.segmentSize) {
      return false;
    }
    long ptr = segment.getAddress();
    while(this.offset < this.segmentSize) {
      // next blockSize
      this.blockSize = UnsafeAccess.toInt(ptr + this.offset + SIZE_OFFSET);
      int dictId = UnsafeAccess.toInt(ptr + this.offset + DICT_VER_OFFSET);
      int compSize = UnsafeAccess.toInt(ptr + this.offset + COMP_SIZE_OFFSET);
      checkBuffer(this.blockSize);
      if (dictId >= 0) {
        int decompSize = this.codec.decompress(ptr + this.offset + COMP_META_SIZE, compSize,
          this.bufPtr, this.bufferSize, dictId);
        if (decompSize == 0) {
          // Failed to find dictionary - skip the block
          this.offset += compSize + COMP_META_SIZE;
          this.blockOffset = 0;
          continue;
        }
      } else if (dictId == -1) {
        UnsafeAccess.copy(ptr + this.offset + COMP_META_SIZE, this.bufPtr, this.blockSize);
        compSize = this.blockSize;
      } else {
        LOG.error(
          "Segment size={} offset={} uncompressed={} compressed={} dictId={} index={} total items={}",
          segment.getSegmentDataSize(), offset, this.blockSize, compSize, dictId, currentIndex,
          segment.getTotalItems());
        Thread.dumpStack();
        throw new RuntimeException();
      }
      // Advance segment offset
      this.offset += compSize + COMP_META_SIZE;
      this.blockOffset = 0;
      return true;
    }
    return false;
  }

  public boolean hasNext() {
    //return currentIndex < segment.getTotalItems();
    return this.offset < this.segmentSize || (this.blockOffset < this.blockSize);
  }

  public boolean next() {

    long ptr = this.bufPtr;

    int keySize = Utils.readUVInt(ptr + this.blockOffset);
    int keySizeSize = Utils.sizeUVInt(keySize);
    this.blockOffset += keySizeSize;
    int valueSize = Utils.readUVInt(ptr + this.blockOffset);
    int valueSizeSize = Utils.sizeUVInt(valueSize);
    this.blockOffset += valueSizeSize;
    this.blockOffset += keySize + valueSize;
    this.currentIndex++;
    if (this.blockOffset >= this.blockSize) {
      return nextBlock();
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
    return Utils.readUVInt(this.bufPtr + this.blockOffset);
  }

  /**
   * Get current value size
   * @return value size
   */

  public final int valueLength() {
    long ptr = this.bufPtr;
    int off = this.blockOffset;
    int keySize = Utils.readUVInt(ptr + off);
    int keySizeSize = Utils.sizeUVInt(keySize);
    off += keySizeSize;
    return Utils.readUVInt(ptr + off);
  }

  /**
   * Get current key's address
   * @return keys address
   */
  public final long keyAddress() {
    long ptr = this.bufPtr;
    int off = this.blockOffset;
    int keySize = Utils.readUVInt(ptr + off);
    int keySizeSize = Utils.sizeUVInt(keySize);
    off += keySizeSize;
    int valueSize = Utils.readUVInt(ptr + off);
    int valueSizeSize = Utils.sizeUVInt(valueSize);
    off += valueSizeSize;
    return ptr + off;
  }

  /**
   * Get current value's address
   * @return values address
   */
  public final long valueAddress() {
    long ptr = this.bufPtr;
    int off = this.blockOffset;
    int keySize = Utils.readUVInt(ptr + off);
    int keySizeSize = Utils.sizeUVInt(keySize);
    off += keySizeSize;
    int valueSize = Utils.readUVInt(ptr + off);
    int valueSizeSize = Utils.sizeUVInt(valueSize);
    off += valueSizeSize + keySize;
    return ptr + off;
  }

  @Override
  public void close() throws IOException {
    segment.readUnlock();
    if (this.bufPtr != 0) {
      UnsafeAccess.free(this.bufPtr);
    }
  }

  @Override
  public int getKey(ByteBuffer b) {
    int keySize = keyLength();
    long keyAddress = keyAddress();
    if (keySize <= b.remaining()) {
      UnsafeAccess.copy(keyAddress, b, keySize);
    }
    return keySize;
  }

  @Override
  public int getValue(ByteBuffer b) {
    int valueSize = valueLength();
    long valueAddress = valueAddress();
    if (valueSize <= b.remaining()) {
      UnsafeAccess.copy(valueAddress, b, valueSize);
    }
    return valueSize;
  }

  @Override
  public int getKey(byte[] buffer, int offset) throws IOException {
    int keySize = keyLength();
    if (keySize > buffer.length - offset) {
      return keySize;
    }
    long keyAddress = keyAddress();
    UnsafeAccess.copy(keyAddress, buffer, offset, keySize);
    return keySize;
  }

  @Override
  public int getValue(byte[] buffer, int offset) throws IOException {
    int valueSize = valueLength();
    if (valueSize > buffer.length - offset) {
      return valueSize;
    }
    long valueAddress = valueAddress();
    UnsafeAccess.copy(valueAddress, buffer, offset, valueSize);
    return valueSize;
  }

  @Override
  public Segment getSegment() {
    return this.segment;
  }

  @Override
  public long getOffset() {
    return this.offset;
  }

  @Override
  public boolean isDirect() {
    return true;
  }

  public long getBufferAddress() {
    return this.bufPtr;
  }

  public int getBufferSize() {
    return this.bufferSize;
  }
}

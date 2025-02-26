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
// Copyright (c) Onecache, Inc.
// SPDX-License-Identifier: MPL-2.0

package com.carrotdata.cache.support;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.carrotdata.cache.Cache;
import com.carrotdata.cache.jmx.CacheJMXSink;
import com.carrotdata.cache.util.CacheConfig;
import com.carrotdata.cache.util.LockSupport;
import com.carrotdata.cache.util.UnsafeAccess;
import com.carrotdata.cache.util.Utils;

public class Memcached {
  /** Logger */
  private static final Logger LOG = LoggerFactory.getLogger(Memcached.class);

  public static class Record {
    private final static byte[] VALUE = "VALUE".getBytes();
    public byte[] value = null;
    public int offset = 0;
    public int size = 0;
    public long cas = 0;
    public long expire = 0;
    public int flags = 0;
    public boolean error;

    /**
     * Write record to the buffer (serialize) TODO test
     * @param keyPtr key address
     * @param keySize key size
     * @param bufferPtr buffer address
     * @param bufferSize buffer size
     * @return serialized size (can be larger that bufferSize)
     */
    public int write(long keyPtr, int keySize, long bufferPtr, int bufferSize, boolean withCAS) {
      // VALUE <key> <flags> <bytes> [<cas unique>]\r\n
      // <data block>\r\n
      int sersize = serializedSize(keySize, withCAS);
      if (sersize > bufferSize - 5 /* END\r\n */) return sersize;
      int off = 0;
      // VALUE
      UnsafeAccess.copy(VALUE, 0, bufferPtr, 5);
      off += 5;
      UnsafeAccess.putByte(bufferPtr + off, (byte) ' ');
      off += 1;
      // key
      UnsafeAccess.copy(keyPtr, bufferPtr + off, keySize);
      off += keySize;
      UnsafeAccess.putByte(bufferPtr + off, (byte) ' ');
      off += 1;
      int l = Utils.longToStrDirect(bufferPtr + off, bufferSize - off, this.flags);
      off += l;
      UnsafeAccess.putByte(bufferPtr + off, (byte) ' ');
      off += 1;
      l = Utils.longToStrDirect(bufferPtr + off, bufferSize - off, this.size);
      off += l;
      if (withCAS) {
        UnsafeAccess.putByte(bufferPtr + off, (byte) ' ');
        off += 1;
        l = Utils.longToStrDirect(bufferPtr + off, bufferSize - off, this.cas);
        off += l;
      }
      UnsafeAccess.putByte(bufferPtr + off, (byte) '\r');
      off += 1;
      UnsafeAccess.putByte(bufferPtr + off, (byte) '\n');
      off += 1;
      // value
      UnsafeAccess.copy(this.value, this.offset, bufferPtr + off, this.size);
      off += this.size;
      UnsafeAccess.putByte(bufferPtr + off, (byte) '\r');
      off += 1;
      UnsafeAccess.putByte(bufferPtr + off, (byte) '\n');
      off += 1;
      return off;
    }

    private int serializedSize(int keySize, boolean withCAS) {

      int sersize = 5 /* VALUE */ + 3 /* spaces */ + 4 /* 2 CRLF */ + keySize + size
          + Utils.sizeAsStr(size) + Utils.sizeAsStr(flags);
      if (withCAS) {
        sersize += 1 + Utils.sizeAsStr(cas);
      }
      return sersize;
    }
  }

  public static enum OpResult {
    STORED, NOT_STORED, EXISTS, NOT_FOUND, DELETED, TOUCHED, ERROR;
  }

  private static int INIT_SIZE = 1 << 16;

  private static ThreadLocal<byte[]> buffer = new ThreadLocal<byte[]>() {
    @Override
    protected byte[] initialValue() {
      return new byte[INIT_SIZE];
    }
  };

  private static ThreadLocal<Long> memory = new ThreadLocal<Long>() {
    protected Long initialValue() {
      long ptr = UnsafeAccess.mallocZeroed(INIT_SIZE);
      return ptr;
    }
  };

  private static ThreadLocal<Long> memorySize = new ThreadLocal<Long>() {
    @Override
    protected Long initialValue() {
      return (long) INIT_SIZE;
    }
  };

  private static void allocBuffer(int sizeRequired) {
    byte[] b = buffer.get();
    if (sizeRequired > 100000) {
      LOG.error("allocBuffer:{}", sizeRequired);
    }
    if (b.length < sizeRequired) {
      b = new byte[sizeRequired];
      buffer.set(b);
    }
  }

  private static void allocMemory(int sizeRequired) {
    if (memorySize.get() >= sizeRequired) {
      return;
    }
    UnsafeAccess.free(memory.get());
    memorySize.set((long) sizeRequired);
    long ptr = UnsafeAccess.mallocZeroed(sizeRequired);
    memory.set(ptr);
  }

  private static void reallocBuffer(int sizeRequired) {
    byte[] b = buffer.get();
    if (b.length < sizeRequired) {
      byte[] bb = new byte[sizeRequired];
      System.arraycopy(b, 0, bb, 0, b.length);
      buffer.set(bb);
    }
  }

  private static void reallocBufferNoCopy(int sizeRequired) {
    byte[] b = buffer.get();
    if (b.length < sizeRequired) {
      byte[] bb = new byte[sizeRequired];
      buffer.set(bb);
    }
  }

  private static void reallocMemory(int sizeRequired) {
    long ptr = memory.get();
    long size = memorySize.get();
    if (size >= sizeRequired) {
      return;
    }
    memorySize.set((long) sizeRequired);
    long $ptr = UnsafeAccess.mallocZeroed(sizeRequired);
    UnsafeAccess.copy(ptr, $ptr, size);
    UnsafeAccess.free(memory.get());
    memory.set($ptr);
  }

  private static void reallocMemoryNoCopy(int sizeRequired) {
    long ptr = memory.get();
    long size = memorySize.get();
    if (size >= sizeRequired) {
      return;
    }
    memorySize.set((long) sizeRequired);
    long $ptr = UnsafeAccess.mallocZeroed(sizeRequired);
    UnsafeAccess.free(ptr);
    memory.set($ptr);
  }

  private volatile Cache cache;

  public Memcached(Cache cache) throws IOException {
    CacheConfig config = CacheConfig.getInstance();
    if (!config.isCacheTLSSupported(cache.getName())) {
      throw new IOException("thread-local storage support must be enabled");
    }
    this.cache = cache;
  }

  public Memcached() throws IOException {
    CacheConfig conf = CacheConfig.getInstance();
    String[] cacheNames = conf.getCacheNames();
    if (cacheNames == null || cacheNames.length == 0) {
      throw new IOException("No cache(s) were defined in the configuration file");
    }
    String mainCache = cacheNames[0];
    Cache c = null;
    if (conf.isSaveOnShutdown(mainCache)) {        
      for (int i = 0; i < cacheNames.length; i++) {
        Cache cc = Cache.loadCache(cacheNames[i]);
        if (cc == null) {
          LOG.error("Failed to load cache '{}', will initialize cache from configuration file instead.", cacheNames[i]);
          cache = null;
          // TODO: dispose?
          break;
        }
        if (c != null) {
          c.setVictimCache(cc);
        } else {
          cache = cc;
        }
        c = cc;
      }
    }
    if (cache == null) {
      cache = fromConfig();
      if (cache == null) {
        throw new IOException("No cache(s) were defined in the configuration file");
      }
    }
    checkStatsTask();
    logBaseConfig();
  }

  private void checkStatsTask() {
    if (System.getProperty("STATS_TASK") != null) {
      long interval = 100000;
      try {
        interval = Long.parseLong(System.getProperty("STATS_TASK_INTERVAL")) * 1000;
      } catch (NumberFormatException e) {
        LOG.warn("STATS_TASK_INTERVAL is not a number");
      } catch(NullPointerException e) {
        // swallow
      }
      this.cache.startStatsTask(interval);
    }
  }

  private void logBaseConfig() {
    Cache c = this.cache;
    logCacheConf(c);
  }
  
  private void logCacheConf(Cache c) {
    LOG.info("******************  {} ******************************", c.getName());    
    LOG.info("Cache type             : {}", c.getCacheType());
    LOG.info("Max memory             : {}", c.getMaximumCacheSize());
    LOG.info("Total allocd memory    : {}", c.getTotalAllocated());
    LOG.info("Storage allocd memory  : {}", c.getStorageAllocated());
    LOG.info("Index allocd memory    : {}", c.getEngine().getMemoryIndex().getAllocatedMemory());
    LOG.info("Compression enabled    : {}", c.getCacheConfig().isCacheCompressionEnabled(c.getName()));
    if (c.getCacheConfig().isCacheCompressionEnabled(c.getName())) {
      LOG.info("Compression codec      : {}", c.getCacheConfig().getCacheCompressionCodecType(c.getName()));
      LOG.info("Compression level      : {}", c.getCacheConfig().getCacheCompressionLevel(c.getName()));
    }
    LOG.info("******************************************************");    
    Cache cc = c.getVictimCache();
    if (cc != null) {
      LOG.info("Cache {}, victim is {}", c.getName(), cc);
    }
    if (cc != null) {
      logCacheConf(cc);
    }
  }
  
  private Cache fromConfig() throws IOException {
    long t1 = System.currentTimeMillis();
    CacheConfig conf = CacheConfig.getInstance();
    String[] cacheNames = conf.getCacheNames();
    Cache cache = null, mainCache = null;
    for (String name : cacheNames) {
      if (!conf.isCacheTLSSupported(name)) {
        conf.setCacheTLSSupported(name, true);
      }
      Cache c = new Cache(name);
      LOG.info("Create cache {}, victim is {}", c.getName(), c.getVictimCache());

      if (mainCache == null) {
        mainCache = c;
      }
      if (cache != null) {
        cache.setVictimCache(c);
        LOG.info("Set victim cache {} -> {}", cache.getName(), c.getName());
      }
      cache = c;
    }
    long t2 = System.currentTimeMillis();
    LOG.info("Create cache from config time={}ms", t2 - t1);
    return mainCache;
  }

  public boolean isAsyncPreferred() {
    return this.cache.isAsyncPreferredSome();
  }
  
  /************** Storage commands ******************/

  /**
   * Set operation
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @param value value buffer
   * @param valueOffset value offset
   * @param valueSize value size
   * @param flags flags
   * @param expTime expiration time in ms since 01011970
   * @return operation result
   */
  public OpResult set(byte[] key, int keyOffset, int keySize, byte[] value, int valueOffset,
      int valueSize, int flags, long expTime) {
    if (expTime < 0) {
      try {
        boolean result = cache.expire(key, keyOffset, keySize);
        return result ? OpResult.STORED : OpResult.NOT_STORED;
      } catch (IOException e) {
        LOG.error("Error:", e);
        return OpResult.ERROR;
      }
    }
    expTime = adjustExpire(expTime);

    allocBuffer(valueSize + Utils.SIZEOF_INT);
    byte[] b = buffer.get();
    // Copy value
    System.arraycopy(value, valueOffset, b, 0, valueSize);
    // Add flags
    UnsafeAccess.putInt(b, valueSize, flags);

    try {
      boolean result =
          cache.put(key, keyOffset, keySize, b, 0, valueSize + Utils.SIZEOF_INT, expTime);
      return result ? OpResult.STORED : OpResult.NOT_STORED;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return OpResult.ERROR;
    }
  }

  /**
   * Set operation
   * @param keyPtr key address
   * @param keySize key size
   * @param valuePtr value address
   * @param valueSize value size
   * @param flags flags
   * @param expTime expiration time
   * @return operation result
   */
  public OpResult set(long keyPtr, int keySize, long valuePtr, int valueSize, int flags,
      long expTime) {
    if (expTime < 0) {
      try {
        boolean result = cache.expire(keyPtr, keySize);
        return result ? OpResult.STORED : OpResult.NOT_STORED;
      } catch (IOException e) {
        LOG.error("Error:", e);
        return OpResult.ERROR;
      }
    }
    expTime = adjustExpire(expTime);

    allocMemory(valueSize + Utils.SIZEOF_INT);
    long ptr = memory.get();
    UnsafeAccess.copy(valuePtr, ptr, valueSize);
    // Add flags
    UnsafeAccess.putInt(ptr + valueSize, flags);

    try {
      boolean result = cache.put(keyPtr, keySize, ptr, valueSize + Utils.SIZEOF_INT, expTime);
      return result ? OpResult.STORED : OpResult.NOT_STORED;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return OpResult.ERROR;
    }
  }

  /**
   * Set operation
   * @param keyPtr key address
   * @param keySize key size
   * @param valuePtr value address
   * @param valueSize value size
   * @param flags flags
   * @param expTime expiration time
   * @return operation result
   */
  public OpResult set(long keyPtr, int keySize, byte[] value, int valueOffset, int valueSize,
      int flags, long expTime) {
    if (expTime < 0) {
      try {
        boolean result = cache.expire(keyPtr, keySize);
        return result ? OpResult.STORED : OpResult.NOT_STORED;
      } catch (IOException e) {
        LOG.error("Error:", e);
        return OpResult.ERROR;
      }
    }
    expTime = adjustExpire(expTime);

    allocMemory(valueSize + Utils.SIZEOF_INT);
    long ptr = memory.get();
    UnsafeAccess.copy(value, valueOffset, ptr, valueSize);
    // Add flags
    UnsafeAccess.putInt(ptr + valueSize, flags);

    try {
      boolean result = cache.put(keyPtr, keySize, ptr, valueSize + Utils.SIZEOF_INT, expTime);
      return result ? OpResult.STORED : OpResult.NOT_STORED;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return OpResult.ERROR;
    }
  }

  /**
   * Add operation (atomic)
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @param value value buffer
   * @param valueOffset value offset
   * @param valueSize value size
   * @param flags flags
   * @param expTime expiration time
   * @return operation result
   */
  public OpResult add(byte[] key, int keyOffset, int keySize, byte[] value, int valueOffset,
      int valueSize, int flags, long expTime) {
    // This operation is atomic
    try {
      if (expTime < 0) {
        boolean result = cache.expire(key, keyOffset, keySize);
        return result ? OpResult.STORED : OpResult.NOT_STORED;
      }
      expTime = adjustExpire(expTime);

      allocBuffer(valueSize + Utils.SIZEOF_INT);
      byte[] b = buffer.get();
      // Copy value
      System.arraycopy(value, valueOffset, b, 0, valueSize);
      // Add flags
      UnsafeAccess.putInt(b, valueSize, flags);

      LockSupport.lock(key, keyOffset, keySize);
      if (cache.existsExact(key, keyOffset, keySize)) {
        return OpResult.NOT_STORED;
      }
      boolean result =
          cache.put(key, keyOffset, keySize, b, 0, valueSize + Utils.SIZEOF_INT, expTime);
      return result ? OpResult.STORED : OpResult.ERROR;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return OpResult.ERROR;
    } finally {
      LockSupport.unlock(key, keyOffset, keySize);
    }
  }

  /**
   * Add operation (atomic)
   * @param keyPtr key address
   * @param keySize key size
   * @param valuePtr value address
   * @param valueSize value size
   * @param flags flags
   * @param expTime expiration time
   * @return operation result
   */
  public OpResult add(long keyPtr, int keySize, long valuePtr, int valueSize, int flags,
      long expTime) {
    // This operation is atomic

    try {
      if (expTime < 0) {
        boolean result = cache.expire(keyPtr, keySize);
        return result ? OpResult.STORED : OpResult.NOT_STORED;
      }
      expTime = adjustExpire(expTime);

      allocMemory(valueSize + Utils.SIZEOF_INT);
      long ptr = memory.get();
      UnsafeAccess.copy(valuePtr, ptr, valueSize);
      // Add flags
      UnsafeAccess.putInt(ptr + valueSize, flags);

      LockSupport.lock(keyPtr, keySize);
      if (cache.existsExact(keyPtr, keySize)) {
        return OpResult.NOT_STORED;
      }
      boolean result = cache.put(keyPtr, keySize, ptr, valueSize + Utils.SIZEOF_INT, expTime);

      return result ? OpResult.STORED : OpResult.ERROR;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return OpResult.ERROR;
    } finally {
      LockSupport.unlock(keyPtr, keySize);
    }
  }

  /**
   * Replace (atomic)
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @param value value buffer
   * @param valueOffset value offset
   * @param valueSize value size
   * @param flags flags
   * @param expTime expiration time
   * @return operation result
   */
  public OpResult replace(byte[] key, int keyOffset, int keySize, byte[] value, int valueOffset,
      int valueSize, int flags, long expTime) {
    // This operation is atomic
    try {
      if (expTime < 0) {
        boolean result = cache.expire(key, keyOffset, keySize);
        return result ? OpResult.STORED : OpResult.NOT_STORED;
      }
      expTime = adjustExpire(expTime);

      allocBuffer(valueSize + Utils.SIZEOF_INT);
      byte[] b = buffer.get();
      // Copy value
      System.arraycopy(value, valueOffset, b, 0, valueSize);
      // Add flags
      UnsafeAccess.putInt(b, valueSize, flags);

      LockSupport.lock(key, keyOffset, keySize);
      if (!cache.existsExact(key, keyOffset, keySize)) {
        return OpResult.NOT_STORED;
      }
      boolean result =
          cache.put(key, keyOffset, keySize, b, 0, valueSize + Utils.SIZEOF_INT, expTime);
      return result ? OpResult.STORED : OpResult.ERROR;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return OpResult.ERROR;
    } finally {
      LockSupport.unlock(key, keyOffset, keySize);
    }
  }

  /**
   * Replace operation (atomic)
   * @param keyPtr key address
   * @param keySize key size
   * @param valuePtr value address
   * @param valueSize value size
   * @param flags flags
   * @param expTime expiration time
   * @return operation result
   */
  public OpResult replace(long keyPtr, int keySize, long valuePtr, int valueSize, int flags,
      long expTime) {
    // This operation is atomic
    try {
      if (expTime < 0) {
        boolean result = cache.expire(keyPtr, keySize);
        return result ? OpResult.STORED : OpResult.NOT_STORED;
      }
      expTime = adjustExpire(expTime);

      allocMemory(valueSize + Utils.SIZEOF_INT);
      long ptr = memory.get();
      UnsafeAccess.copy(valuePtr, ptr, valueSize);
      // Add flags
      UnsafeAccess.putInt(ptr + valueSize, flags);

      LockSupport.lock(keyPtr, keySize);
      if (!cache.existsExact(keyPtr, keySize)) {
        return OpResult.NOT_STORED;
      }
      boolean result = cache.put(keyPtr, keySize, ptr, valueSize + Utils.SIZEOF_INT, expTime);
      return result ? OpResult.STORED : OpResult.ERROR;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return OpResult.ERROR;
    } finally {
      LockSupport.unlock(keyPtr, keySize);
    }
  }

  /**
   * Append operation (atomic)
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @param value value buffer
   * @param valueOffset value offset
   * @param valueSize value size
   * @param flags flags
   * @param expTime expiration time
   * @return operation result
   */
  public OpResult append(byte[] key, int keyOffset, int keySize, byte[] value, int valueOffset,
      int valueSize, int flags, long expTime) {
    // This operation is atomic
    try {
      if (expTime < 0) {
        boolean result = cache.expire(key, keyOffset, keySize);
        return result ? OpResult.STORED : OpResult.NOT_STORED;
      }
      expTime = adjustExpire(expTime);

      LockSupport.lock(key, keyOffset, keySize);
      Record r = get(key, keyOffset, keySize);
      if (r.value == null) {
        return OpResult.NOT_STORED;
      }
      int size = r.size;
      // r.offset = 0
      int requiredSize = size + valueSize + Utils.SIZEOF_INT;
      reallocBuffer(requiredSize);
      byte[] b = buffer.get();
      // Copy value
      System.arraycopy(value, valueOffset, b, size, valueSize);
      // Add flags
      UnsafeAccess.putInt(b, size + valueSize, flags);
      boolean result = cache.put(key, keyOffset, keySize, b, 0, requiredSize, expTime);
      return result ? OpResult.STORED : OpResult.NOT_STORED;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return OpResult.ERROR;
    } finally {
      LockSupport.unlock(key, keyOffset, keySize);
    }
  }

  /**
   * Append operation (atomic)
   * @param keyPtr key address
   * @param keySize key size
   * @param valuePtr value address
   * @param valueSize value size
   * @param flags flags
   * @param expTime expiration time
   * @return operation result
   */
  public OpResult append(long keyPtr, int keySize, long valuePtr, int valueSize, int flags,
      long expTime) {
    // This operation is atomic
    try {
      if (expTime < 0) {
        boolean result = cache.expire(keyPtr, keySize);
        return result ? OpResult.STORED : OpResult.NOT_STORED;
      }
      expTime = adjustExpire(expTime);

      LockSupport.lock(keyPtr, keySize);
      Record r = get(keyPtr, keySize);
      if (r.value == null) {
        return OpResult.NOT_STORED;
      }
      int size = r.size;
      // r.offset = 0
      int requiredSize = size + valueSize + Utils.SIZEOF_INT;
      reallocMemory(requiredSize);
      long ptr = memory.get();
      // Copy existing
      UnsafeAccess.copy(r.value, r.offset, ptr, size);
      // Copy value
      UnsafeAccess.copy(valuePtr, ptr + size, valueSize);
      // Add flags
      UnsafeAccess.putInt(ptr + size + valueSize, flags);
      boolean result = cache.put(keyPtr, keySize, ptr, requiredSize, expTime);
      return result ? OpResult.STORED : OpResult.NOT_STORED;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return OpResult.ERROR;
    } finally {
      LockSupport.unlock(keyPtr, keySize);
    }
  }

  /**
   * Append (atomic)
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @param value value buffer
   * @param valueOffset value offset
   * @param valueSize value size
   * @param flags flags
   * @param expTime expiration time
   * @return operation result
   */
  public OpResult prepend(byte[] key, int keyOffset, int keySize, byte[] value, int valueOffset,
      int valueSize, int flags, long expTime) {
    // This operation is atomic
    try {
      if (expTime < 0) {
        boolean result = cache.expire(key, keyOffset, keySize);
        return result ? OpResult.STORED : OpResult.NOT_STORED;
      }
      expTime = adjustExpire(expTime);

      LockSupport.lock(key, keyOffset, keySize);
      Record r = get(key, keyOffset, keySize);
      if (r.value == null) {
        return OpResult.NOT_STORED;
      }
      int size = r.size;
      // r.offset = 0
      int requiredSize = size + valueSize + Utils.SIZEOF_INT;
      reallocBufferNoCopy(requiredSize);
      byte[] b = buffer.get();
      // Copy existing
      System.arraycopy(r.value, r.offset, b, valueSize, size);
      // Copy value
      System.arraycopy(value, valueOffset, b, 0, valueSize);
      // Add flags
      UnsafeAccess.putInt(b, size + valueSize, flags);
      boolean result = cache.put(key, keyOffset, keySize, b, 0, requiredSize, expTime);
      return result ? OpResult.STORED : OpResult.NOT_STORED;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return OpResult.ERROR;
    } finally {
      LockSupport.unlock(key, keyOffset, keySize);
    }
  }

  /**
   * Prepend operation (atomic)
   * @param keyPtr key address
   * @param keySize key size
   * @param valuePtr value address
   * @param valueSize value size
   * @param flags flags
   * @param expTime expiration time
   * @return operation result
   */
  public OpResult prepend(long keyPtr, int keySize, long valuePtr, int valueSize, int flags,
      long expTime) {
    // This operation is atomic
    try {
      if (expTime < 0) {
        boolean result = cache.expire(keyPtr, keySize);
        return result ? OpResult.STORED : OpResult.NOT_STORED;
      }
      expTime = adjustExpire(expTime);

      LockSupport.lock(keyPtr, keySize);
      Record r = get(keyPtr, keySize);
      if (r.value == null) {
        return OpResult.NOT_STORED;
      }
      int size = r.size;
      // r.offset = 0
      int requiredSize = size + valueSize + Utils.SIZEOF_INT;
      reallocMemoryNoCopy(requiredSize);
      long ptr = memory.get();
      // Copy existing
      UnsafeAccess.copy(r.value, r.offset, ptr + valueSize, size);
      // Copy value
      UnsafeAccess.copy(valuePtr, ptr, valueSize);
      // Add flags
      UnsafeAccess.putInt(ptr + size + valueSize, flags);
      boolean result = cache.put(keyPtr, keySize, ptr, requiredSize, expTime);
      return result ? OpResult.STORED : OpResult.NOT_STORED;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return OpResult.ERROR;
    } finally {
      LockSupport.unlock(keyPtr, keySize);
    }
  }

  /**
   * CAS (compare-and-swap) (atomic) can be optimized
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @param value value buffer
   * @param valueOffset value offset
   * @param valueSize value size
   * @param flags flags
   * @param expTime expiration time
   * @param cas CAS unique
   * @return operation result
   */
  public OpResult cas(byte[] key, int keyOffset, int keySize, byte[] value, int valueOffset,
      int valueSize, int flags, long expTime, long cas) {
    // This operation is atomic
    try {

      LockSupport.lock(key, keyOffset, keySize);
      Record r = get(key, keyOffset, keySize);
      if (r.value == null) {
        return OpResult.NOT_FOUND;
      }
      long $cas = computeCAS(r.value, r.offset, r.size);
      if (cas != $cas) {
        return OpResult.EXISTS;
      }
      if (expTime < 0) {
        boolean result = cache.expire(key, keyOffset, keySize);
        return result ? OpResult.STORED : OpResult.NOT_FOUND;
      }
      expTime = adjustExpire(expTime);

      int requiredSize = valueSize + Utils.SIZEOF_INT;
      allocBuffer(requiredSize);
      byte[] b = buffer.get();
      // Copy value
      System.arraycopy(value, valueOffset, b, 0, valueSize);
      // Add flags
      UnsafeAccess.putInt(b, valueSize, flags);
      boolean result = cache.put(key, keyOffset, keySize, b, 0, requiredSize, expTime);
      return result ? OpResult.STORED : OpResult.NOT_STORED;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return OpResult.ERROR;
    } finally {
      LockSupport.unlock(key, keyOffset, keySize);
    }
  }

  /**
   * CAS (compare-and-swap) operation (atomic)
   * @param keyPtr key address
   * @param keySize key size
   * @param valuePtr value address
   * @param valueSize value size
   * @param flags flags
   * @param expTime expiration time
   * @param cas CAS unique
   * @return operation result
   */
  public OpResult cas(long keyPtr, int keySize, long valuePtr, int valueSize, int flags,
      long expTime, long cas) {
    // This operation is atomic
    try {
      LockSupport.lock(keyPtr, keySize);
      Record r = get(keyPtr, keySize);
      if (r.value == null) {
        return OpResult.NOT_FOUND;
      }
      long $cas = computeCAS(r.value, r.offset, r.size);
      if (cas != $cas) {
        return OpResult.EXISTS;
      }
      if (expTime < 0) {
        boolean result = cache.expire(keyPtr, keySize);
        return result ? OpResult.STORED : OpResult.NOT_FOUND;
      }
      expTime = adjustExpire(expTime);

      int requiredSize = valueSize + Utils.SIZEOF_INT;
      allocMemory(requiredSize);
      long ptr = memory.get();
      // Copy value
      UnsafeAccess.copy(valuePtr, ptr, valueSize);
      // Add flags
      UnsafeAccess.putInt(ptr + valueSize, flags);
      boolean result = cache.put(keyPtr, keySize, ptr, requiredSize, expTime);
      return result ? OpResult.STORED : OpResult.NOT_STORED;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return OpResult.ERROR;
    } finally {
      LockSupport.unlock(keyPtr, keySize);
    }
  }

  /**************** Retrieval commands *****************/

  /**
   * Get value by key
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @return result record
   */
  public Record get(byte[] key, int keyOffset, int keySize) {
    Record result = new Record();

    byte[] buf = buffer.get();
    try {
      long size = cache.get(key, keyOffset, keySize, buf, 0);
      while (size > buf.length) {
        allocBuffer((int) size);
        buf = buffer.get();
        size = cache.get(key, keyOffset, keySize, buf, 0);
      }
      if (size < 0) {
        // Not found
        return result;
      }
      result.value = buf;
      result.offset = 0;
      result.size = (int) (size - Utils.SIZEOF_INT);
      result.flags = UnsafeAccess.toInt(buf, result.size);
      return result;
    } catch (IOException e) {
      LOG.error("Error:", e);
      result.error = true;
      return result;
    }
  }

  /**
   * Get value by key
   * @param keyPtr key address
   * @param keySize key size
   * @return result record
   */
  public Record get(long keyPtr, int keySize) {
    Record result = new Record();

    byte[] buf = buffer.get();
    try {
      long size = cache.get(keyPtr, keySize, true, buf, 0);

      while (size > buf.length) {
        allocBuffer((int) size);
        buf = buffer.get();
        size = cache.get(keyPtr, keySize, true, buf, 0);
      }
      if (size < 0) {
        // Not found
        return result;
      }
      result.value = buf;
      result.offset = 0;
      result.size = (int) (size - Utils.SIZEOF_INT);
      result.flags = UnsafeAccess.toInt(buf, result.size);
      return result;
    } catch (IOException e) {
      LOG.error("Error:", e);
      result.error = true;
      return result;
    }
  }

  /**
   * Get and touch value by key
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @param newExpire new expire
   * @return result record
   */
  public Record gat(byte[] key, int keyOffset, int keySize, long newExpire) {
    try {

      LockSupport.lock(key, keyOffset, keySize);
      Record r = get(key, keyOffset, keySize);
      if (r.value != null) {
        long expire = touch(key, keyOffset, keySize, newExpire);
        r.expire = expire;
      }
      return r;
    } finally {
      LockSupport.unlock(key, keyOffset, keySize);
    }
  }

  /**
   * Get and touch value by key
   * @param keyPtr key address
   * @param keySize key size
   * @return
   */
  public Record gat(long keyPtr, int keySize, long newExpire) {
    try {
      LockSupport.lock(keyPtr, keySize);
      Record r = get(keyPtr, keySize);
      if (r.value != null) {
        long expire = touch(keyPtr, keySize, newExpire);
        r.expire = expire;
      }
      return r;
    } finally {
      LockSupport.unlock(keyPtr, keySize);
    }
  }

  /**
   * Get value by key with CAS
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @return result record
   */
  public Record gets(byte[] key, int keyOffset, int keySize) {
    Record result = new Record();

    byte[] buf = buffer.get();
    try {
      long size = cache.get(key, keyOffset, keySize, buf, 0);
      while (size > buf.length) {
        allocBuffer((int) size);
        buf = buffer.get();
        size = cache.get(key, keyOffset, keySize, buf, 0);
      }
      if (size < 0) {
        // Not found
        return result;
      }
      result.value = buf;
      result.offset = 0;
      result.size = (int) (size - Utils.SIZEOF_INT);
      result.flags = UnsafeAccess.toInt(buf, result.size);
      result.cas = computeCAS(buf, 0, result.size);
      return result;
    } catch (IOException e) {
      LOG.error("Error:", e);
      result.error = true;
      return result;
    }
  }

  /**
   * Get value by key with CAS
   * @param keyPtr key address
   * @param keySize key size
   * @return result record
   */
  public Record gets(long keyPtr, int keySize) {
    Record result = new Record();

    byte[] buf = buffer.get();
    try {
      long size = cache.get(keyPtr, keySize, true, buf, 0);
      while (size > buf.length) {
        allocBuffer((int) size);
        buf = buffer.get();
        size = cache.get(keyPtr, keySize, true, buf, 0);
      }
      if (size < 0) {
        // Not found
        return result;
      }
      result.value = buf;
      result.offset = 0;
      result.size = (int) (size - Utils.SIZEOF_INT);
      result.flags = UnsafeAccess.toInt(buf, result.size);
      result.cas = computeCAS(buf, 0, result.size);
      return result;
    } catch (IOException e) {
      LOG.error("Error:", e);
      result.error = true;
      return result;
    }
  }

  /**
   * Get and touch value by key with CAS
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @param newExpire new expire
   * @return result record
   */
  public Record gats(byte[] key, int keyOffset, int keySize, long newExpire) {
    try {
      LockSupport.lock(key, keyOffset, keySize);
      Record r = gets(key, keyOffset, keySize);
      if (r.value != null) {
        long expire = touch(key, keyOffset, keySize, newExpire);
        r.expire = expire;
      }
      return r;
    } finally {
      LockSupport.unlock(key, keyOffset, keySize);
    }
  }

  /**
   * Get and touch value by key with CAS
   * @param keyPtr key address
   * @param keySize key size
   * @return
   */
  public Record gats(long keyPtr, int keySize, long newExpire) {
    try {
      LockSupport.lock(keyPtr, keySize);
      Record r = gets(keyPtr, keySize);
      if (r.value != null) {
        long expire = touch(keyPtr, keySize, newExpire);
        r.expire = expire;
      }
      return r;
    } finally {
      LockSupport.unlock(keyPtr, keySize);
    }
  }

  /***************** Misc commands **********************/

  /**
   * Touch (sets new expiration time)
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @param expTime new expiration time
   * @return previous expiration time or -1 (if key did exist)
   */
  public long touch(byte[] key, int keyOffset, int keySize, long expTime) {
    try {
      if (expTime < 0) {
        boolean r = cache.expire(key, keyOffset, keySize);
        return r ? 0 : -1;
      }
      expTime = adjustExpire(expTime);
      long v = cache.getAndSetExpire(key, keyOffset, keySize, expTime);
      if (v == -1) return -1;
      return v / 1000;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return 0;
    }
  }

  /**
   * Touch (sets new expiration time)
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @param expTime new expiration time
   * @return previous expiration time or -1 (if key did exist)
   */
  public long touch(long keyPtr, int keySize, long expTime) {
    try {
      if (expTime < 0) {
        boolean r = cache.expire(keyPtr, keySize);
        return r ? 0 : -1;
      }
      expTime = adjustExpire(expTime);
      long v = cache.getAndSetExpire(keyPtr, keySize, expTime);
      if (v == -1) return -1;
      return v / 1000;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return 0;
    }
  }

  /**
   * Delete by key
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @return operation result
   */
  public OpResult delete(byte[] key, int keyOffset, int keySize) {
    try {
      boolean result = cache.delete(key, keyOffset, keySize);
      return result ? OpResult.DELETED : OpResult.NOT_FOUND;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return OpResult.ERROR;
    }
  }

  /**
   * Increment (MUST BE OPTIMIZED)
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @param v - positive
   * @return -1 - error or new value after increment
   * @throws NumberFormatException
   */
  public long incr(byte[] key, int keyOffset, int keySize, long v) throws NumberFormatException {
    if (v < 0) {
      throw new IllegalArgumentException("increment value must be positive");
    }
    try {
      LockSupport.lock(key, keyOffset, keySize);
      Record r = get(key, keyOffset, keySize);
      if (r.value != null) {
        byte[] b = r.value;
        int off = r.offset;
        int size = r.size;
        long val = Utils.strToLong(b, off, size);
        // we ignore buffer overflow - its >> than 20 (maximum number of digits and sign)
        int numDigits = Utils.longToStr(b, off, val + v);
        long expire = cache.getExpire(key, keyOffset, keySize);
        set(key, keyOffset, keySize, b, off, numDigits, r.flags, expire / 1000);
        return val + v;
      }
      return -1;// NOT_FOUND
    } finally {
      LockSupport.unlock(key, keyOffset, keySize);
    }
  }

  /**
   * Increment (MUST BE OPTIMIZED)
   * @param key
   * @param keyOffset
   * @param keySize
   * @param v - positive
   * @return -1 - error or new value after increment
   * @throws NumberFormatException
   */
  public long incr(long keyPtr, int keySize, long v) throws NumberFormatException {
    if (v < 0) {
      throw new IllegalArgumentException("increment value must be positive");
    }
    try {
      LockSupport.lock(keyPtr, keySize);
      Record r = get(keyPtr, keySize);
      if (r.value != null) {
        byte[] b = r.value;
        int off = r.offset;
        int size = r.size;
        long val = Utils.strToLong(b, off, size);
        int numDigits = Utils.longToStr(b, off, val + v);
        long expire = cache.getExpire(keyPtr, keySize);
        set(keyPtr, keySize, b, off, numDigits, r.flags, expire / 1000);
        return val + v;
      }
      return -1;// NOT_FOUND
    } finally {
      LockSupport.unlock(keyPtr, keySize);
    }
  }

  /**
   * Increment (MUST BE OPTIMIZED)
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @param v - positive
   * @return -1 - error or new value after increment
   * @throws NumberFormatException
   */
  public long decr(byte[] key, int keyOffset, int keySize, long v) throws NumberFormatException {
    if (v < 0) {
      throw new IllegalArgumentException("decrement value must be positive");
    }
    try {
      LockSupport.lock(key, keyOffset, keySize);
      Record r = get(key, keyOffset, keySize);
      if (r.value != null) {
        byte[] b = r.value;
        int off = r.offset;
        int size = r.size;
        long val = Utils.strToLong(b, off, size);
        long newValue = val - v;
        if (newValue < 0) {
          newValue = 0;
        }
        int numDigits = Utils.longToStr(b, off, newValue);
        long expire = cache.getExpire(key, keyOffset, keySize);
        set(key, keyOffset, keySize, b, off, numDigits, r.flags, expire / 1000);
        return newValue;
      }
      return -1;// NOT_FOUND
    } finally {
      LockSupport.unlock(key, keyOffset, keySize);
    }
  }

  /**
   * Increment (MUST BE OPTIMIZED)
   * @param key
   * @param keyOffset
   * @param keySize
   * @param v - positive
   * @return -1 - error or new value after increment
   * @throws NumberFormatException
   */
  public long decr(long keyPtr, int keySize, long v) throws NumberFormatException {
    if (v < 0) {
      throw new IllegalArgumentException("decrement value must be positive");
    }
    try {
      LockSupport.lock(keyPtr, keySize);
      Record r = get(keyPtr, keySize);
      if (r.value != null) {
        byte[] b = r.value;
        int off = r.offset;
        int size = r.size;
        long val = Utils.strToLong(b, off, size);
        long newValue = val - v;
        if (newValue < 0) {
          newValue = 0;
        }
        int numDigits = Utils.longToStr(b, off, newValue);
        long expire = cache.getExpire(keyPtr, keySize);
        set(keyPtr, keySize, b, off, numDigits, r.flags, expire / 1000);
        return newValue;
      }
      return -1;// NOT_FOUND
    } finally {
      LockSupport.unlock(keyPtr, keySize);
    }
  }

  /**
   * Delete by key
   * @param key key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @return operation result
   */
  public OpResult delete(long keyPtr, int keySize) {
    try {
      boolean result = cache.delete(keyPtr, keySize);
      return result ? OpResult.DELETED : OpResult.NOT_FOUND;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return OpResult.ERROR;
    }
  }

  /**
   * General cache statistics
   * TODO: works only for single and two-cache configurations 
   * @return list of key-value (key1, value1, key2, value2, ...)
   */
  public List<String> stats() {
    CacheJMXSink sink = new CacheJMXSink(cache);
    List<String> result = sink.asList();
    Cache victim = cache.getVictimCache();
    if (victim != null) {
      sink = new CacheJMXSink(victim);
      result.addAll(sink.asList());
    }
    return result;
  }
  
  /**
   * Expire operation
   * @param keyPtr key buffer
   * @param keyOffset key offset
   * @param keySize key size
   * @return operation result
   */
  public boolean expire(byte[] key, int keyOffset, int keySize) {
    try {
      boolean result = cache.expire(key, keyOffset, keySize);
      return result;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return false;
    }
  }

  /**
   * Expire operation
   * @param keyPtr key address
   * @param keySize key size
   * @return operation result
   */
  public boolean expire(long keyPtr, int keySize) {
    try {
      boolean result = cache.expire(keyPtr, keySize);
      return result;
    } catch (IOException e) {
      LOG.error("Error:", e);
      return false;
    }
  }

  public void flushAll(int timeout) {
    if (timeout == 0) {
      try {
        this.cache = Cache.flushAll(cache);
        this.cache.startStatsTask(-1);
      } catch (IOException e) {
        LOG.error("FlushAll:", e);
      }
    } else {
      Runnable r = () -> {
        try {
          Thread.sleep(timeout * 1000L);
          this.cache = Cache.flushAll(cache);
          this.cache.startStatsTask(-1);
        } catch (Exception e) {
          LOG.error("FlushAll:", e);
        }
      };
      new Thread(r).start();
    }
  }
  
  /*************************** Utility methods ************************/

  long computeCAS(byte[] value, int valueOffset, int valueSize) {
    return Math.abs(Utils.hash64(value, valueOffset, valueSize));
  }

  long computeCAS(long valuePtr, int valueSize) {
    return Math.abs(Utils.hash64(valuePtr, valueSize));
  }

  long adjustExpire(long expire) {
    if (expire == 0) return 0;
    if (expire <= 60 * 60 * 24 * 30) {
      expire = System.currentTimeMillis() + expire * 1000L;
    } else {
      expire = expire * 1000;
    }
    return expire;
  }

  public void dispose() {
    this.cache.dispose();
  }

  public Cache getCache() {
    return this.cache;
  }
}

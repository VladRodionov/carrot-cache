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
package com.carrot.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.carrot.cache.Cache.Type;
import com.carrot.cache.io.ObjectPool;
import com.carrot.cache.util.CarrotConfig;
import com.esotericsoftware.kryo.kryo5.Kryo;
import com.esotericsoftware.kryo.kryo5.io.Input;
import com.esotericsoftware.kryo.kryo5.io.Output;

public class ObjectCache {
  
  public static interface SerdeInitializationListener{
    
    public void initSerde(Kryo kryo);
  }
  
  /** Logger */
  @SuppressWarnings("unused")
  private static final Logger LOG = LogManager.getLogger(ObjectCache.class);
  
  /** Initial buffer size in bytes for Kryo serialization*/
  private final int INITIAL_BUFFER_SIZE = 4096;
  
  /** Native cache instance (off-heap, disk or hybrid)*/
  private Cache cache;
    
  /** Kryo object serialization pool*/
  private ObjectPool<Kryo> kryos;
  
  private Map<Class<?>, Class<?>> keyValueClassMap = new ConcurrentHashMap<Class<?>, Class<?>>();
  
  /** Kryo initialization listeners */
  private List<SerdeInitializationListener> listeners = 
      Collections.synchronizedList(new ArrayList<>());
  
  /** Object pool for Kryo inputs */
  static ObjectPool<Input> inputs;
  
  /** Object pool for Kryo outputs */
  static ObjectPool<Output> outputs;
  
  /**
   * Default constructor
   * @param c native cache instance
   */
  ObjectCache(Cache c){
    Objects.requireNonNull(c, "cache is null");    
    this.cache = c;
    initIOPools();
  }
    
  public void addSerdeInitializationListener(SerdeInitializationListener l) {
    this.listeners.add(l);
  }
  
  public void addKeyValueClasses(Class<?> key, Class<?> value) {
    this.keyValueClassMap.put(key,  value);
  }
  
  private void initIOPools() {
    int poolSize = cache.getCacheConfig().getIOStoragePoolSize(cache.getName());
    if (inputs == null) {
      synchronized(ObjectPool.class) {
        if (inputs == null) {
          inputs = new ObjectPool<Input>(poolSize);
        }
      }
    }
    if (outputs == null) {
      synchronized(ObjectPool.class) {
        if (outputs == null) {
          outputs = new ObjectPool<Output>(poolSize);
        }
      }
    }
    if (kryos == null) {
      synchronized(ObjectPool.class) {
        if (kryos == null) {
          kryos = new ObjectPool<Kryo>(poolSize);
        }
      }
    }
  }
  
  /**
   * Put key - value pair into the cache with expiration (absolute time in ms) 
   * This is operation to bypass admission controller
   * @param key key object
   * @param value value object
   * @param expire expiration time (absolute in ms since 01/01/1970)
   * @return true on success, false  - otherwise
   * @throws IOException
   */
  public boolean put(Object key, Object value, long expire) throws IOException {
    return put(key, value, expire, true);
  }
  
  /**
   * Put key - value pair into the cache with expiration (absolute time in ms) 
   * @param key key object
   * @param value value object
   * @param expire expiration time (absolute)
   * @param force if true - bypass admission controller
   * @return y=true on success, false  - otherwise
   * @throws IOException
   */
  public boolean put(Object key, Object value, long expire, boolean force) throws IOException {
    Objects.requireNonNull(key, "key is null");
    Objects.requireNonNull(value, "value is null");
    Output outKey = getOutput();
    Output outValue = getOutput();
    Kryo kryo = getKryo();
    try {
      kryo.writeObject(outKey, key);
      kryo.writeObject(outValue, value);
      // TODO: cryptographic hashing of a key
      byte[] keyBuffer = outKey.getBuffer();
      int keyLength = outKey.position();
      byte[] valueBuffer = outValue.getBuffer();
      int valueLength = outValue.position();
      boolean result =
          cache.put(keyBuffer, 0, keyLength, valueBuffer, 0, valueLength, expire, force);
      return result;
    } finally {
      release(outKey);
      release(outValue);
      release(kryo);
    }
  }

  /**
   * Get cached value
   * @param key object key
   * @return value or null
   * @throws IOException
   */
  public Object get(Object key) throws IOException {
    Objects.requireNonNull(key, "key is null");
    Output outKey = getOutput();
    Input in = getInput();
    Kryo kryo = getKryo();
    Class<?> valueClass = this.keyValueClassMap.get(key.getClass());
    
    if (valueClass == null) {
      throw new IOException(String.format("Value class is not registered for key class %s", key.getClass()));
    }
    
    try {
      kryo.writeObject(outKey, key);
      byte[] keyBuffer = outKey.getBuffer();
      int keyLength = outKey.position();
      byte[] buf = in.getBuffer();
      while (true) {
        long size = cache.get(keyBuffer, 0, keyLength, buf, 0);
        if (size < 0) {
          return null;
        }
        if (size > buf.length) {
          buf = new byte[(int) size];
          in.setBuffer(buf);
          continue;
        } else {
          in.setBuffer(buf, 0, (int) size);
          break;
        }
      }
      Object value = kryo.readObject(in, valueClass);
      return value;
    } finally {
      release(outKey);
      release(in);
      release(kryo);
    }
  }
  
  /**
   * Delete object by key
   * @param key object key
   * @return true on success, false - otherwise
   * @throws IOException
   */
  public boolean delete(Object key) throws IOException {
    Objects.requireNonNull(key, "key is null");
    Output outKey = getOutput();
    Kryo kryo = getKryo();

    try {
      kryo.writeObject(outKey, key);
      // TODO: cryptographic hashing of a key
      byte[] keyBuffer = outKey.getBuffer();
      int keyLength = outKey.position();
      boolean result =
          cache.delete(keyBuffer, 0, keyLength);
      return result;
    } finally {
      release(outKey);
      release(kryo);
    }
  }
  
  /**
   * Does key exist
   * @param key object key
   * @return true on success, false - otherwise
   * @throws IOException
   */
  public boolean exists(Object key) throws IOException {
    Objects.requireNonNull(key, "key is null");
    Output outKey = getOutput();
    Kryo kryo = getKryo();
    try {
      kryo.writeObject(outKey, key);
      // TODO: cryptographic hashing of a key
      byte[] keyBuffer = outKey.getBuffer();
      int keyLength = outKey.position();
      boolean result =
          cache.exists(keyBuffer, 0, keyLength);
      return result;
    } finally {
      release(outKey);
      release(kryo);
    }
  }
  
  /**
   * Touch the key 
   * @param key object key
   * @return true on success, false - otherwise (key does not exists)
   * @throws IOException
   */
  public boolean touch(Object key) throws IOException {
    Objects.requireNonNull(key, "key is null");
    Output outKey = getOutput();
    Kryo kryo = getKryo();
    try {
      kryo.writeObject(outKey, key);
      // TODO: cryptographic hashing of a key
      byte[] keyBuffer = outKey.getBuffer();
      int keyLength = outKey.position();
      boolean result =
          cache.touch(keyBuffer, 0, keyLength);
      return result;
    } finally {
      release(outKey);
      release(kryo);
    }
  }
  
  /**
   * Shutdown and save cache
   * @throws IOException
   */
  public void shutdown() throws IOException {
    // Shutdown main cache
    this.cache.shutdown();
  }
  
  /**
   * Loads saved object cache.
   * Make sure that CarrotConfig was already set for the cache
   * @param cacheRootDir cache root directory
   * @param cacheName cache name
   * @return object cache or null
   * @throws IOException
   */
  public static ObjectCache loadCache(String cacheRootDir, String cacheName) throws IOException {
    
    Cache c = Cache.loadCache(cacheRootDir, cacheName);
    if (c != null) {  
        return new ObjectCache(c);
    }
    // Else create new
    return null;
  }
  
  /**
   * Get Kryo instance from pool or new one
   * @return Kryo input
   */
  Input getInput() {
    Input in = inputs.poll();
    if (in == null) {
      in = new Input(INITIAL_BUFFER_SIZE);
    } else {
      // for reuse
      in.reset();
    }
    return in;
  }
  
  /**
   * Get Kryo output or new one
   * @return Kryo output
   */
  Output getOutput() {
    Output out = outputs.poll();
    if (out == null) {
      out = new Output(INITIAL_BUFFER_SIZE);
    } else {
      out.reset();
    }
    return out;
  }
  
  /**
   * Get Kryo instance
   * @return instance
   */
  Kryo getKryo() {
    Kryo kryo = kryos.poll();
    if(kryo == null) {
      kryo = new Kryo();
      kryo.setRegistrationRequired(false);
      for (Map.Entry<Class<?>, Class<?>> entry : keyValueClassMap.entrySet()) {
        kryo.register(entry.getKey());
        kryo.register(entry.getValue());
      }
      for (SerdeInitializationListener l:listeners) {
        l.initSerde(kryo);
      }
    } else {
      kryo.reset();
    }
    return kryo;
  }
  
  /**
   * Release input back to the pool
   * @param in Kryo input
   */
  void release(Input in) {
    inputs.offer(in);
  }
  
  /**
   * Release output back to the pool
   * @param out Kryo output
   */
  void release(Output out) {
    outputs.offer(out);
  }
  
  /**
   * Release kryo instance
   * @param kryo
   */
  void release(Kryo kryo) {
    kryos.offer(kryo);
  }
  
  /**
   * Adds shutdown hook
   */
  public void addShutdownHook() {
    this.cache.addShutdownHook();
  }
  
  /**
   * Removes shutdown hook
   */
  public void removeShutdownHook() {
    this.cache.removeShutdownHook();
  }
  
  /**
   * Register JMX metrics 
   */
  public void registerJMXMetricsSink() {
    this.cache.registerJMXMetricsSink();
  }
  
  /**
   * Register JMX metrics with a custom domain name
   * @param domainName
   */
  public void registerJMXMetricsSink(String domainName) {
    this.cache.registerJMXMetricsSink(domainName);
  }
  
  /**
   * Get number of objects in the cache
   * @return number of objects in the cache
   */
  public long size() {
    return this.cache.size();
  }
  
  /**
   * Total number of active items (accessible)
   * @return active number
   */
  public long activeSize() {
    return this.cache.activeSize();
  }
  
  /**
   * Gets memory limit
   * @return memory limit in bytes
   */
  public long getMaximumCacheSize() {
    return this.cache.getMaximumCacheSize();
  }
  
  /**
   * Get memory used as a fraction of memory limit
   *
   * @return memory used fraction
   */
  public double getStorageAllocatedRatio() {
    return this.cache.getStorageAllocatedRatio();
  }
 
  /**
   * Get total used memory (storage)
   *
   * @return used memory
   */
  public long getStorageUsed() {
    return this.cache.getStorageUsed();
  }
  
  /**
   * Get total allocated memory
   *
   * @return total allocated memory
   */
  public long getStorageAllocated() {
    return this.cache.getStorageAllocated();
  }
  
  /**
   * Get total gets
   * @return total gets
   */
  public long getTotalGets() {
    return this.cache.getTotalGets();
  }
  
  /**
   * Total gets size
   * @return size
   */
  public long getTotalGetsSize() {
    return this.cache.getTotalGetsSize();
  }
  
  /**
   * Get total hits
   * @return total hits
   */
  public long getTotalHits() {
    return this.cache.getTotalHits();
  }
  
  /**
   * Get total writes
   * @return total writes
   */
  public long getTotalWrites() {
    return this.cache.getTotalWrites();
  }
  
  /**
   * Get total writes size
   * @return total writes size
   */
  public long getTotalWritesSize() {
    return this.cache.getTotalWritesSize();
  }
  
  /**
   * Get total rejected writes
   * @return total rejected writes
   */
  public long getTotalRejectedWrites() {
    return this.cache.getTotalRejectedWrites();
  }
  
  /**
   * Get cache hit rate
   * @return cache hit rate
   */
  public double getHitRate() {
    return this.cache.getHitRate();
  }
  
  /**
   * For hybrid caches
   * @return hybrid cache hit rate
   */
  public double getOverallHitRate() {
    return this.cache.getOverallHitRate();
  }
  
  /**
   * Get native cache
   * @return cache
   */
  public Cache getNativeCache() {
    return this.cache;
  }
  
  /**
   * Get cache name
   * @return cache name
   */
  public String getName() {
    return this.cache.getName();
  }
  
  /**
   * Get cache type
   * @return cache type
   */
  public Type getCacheType() {
    return this.cache.getCacheType();
  }
  
  /**
   * Get cache configuration
   * @return cache configuration
   */
  public CarrotConfig getCacheConfig() {
    return this.cache.getCacheConfig();
  }
}

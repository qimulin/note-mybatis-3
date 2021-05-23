/**
 *    Copyright 2009-2020 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.cache.decorators;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * 二级缓存缓冲区（暂存区）
 * The 2nd level cache transactional buffer.
 * <p>
 * This class holds all cache entries that are to be added to the 2nd level cache during a Session.
 * Entries are sent to the cache when commit is called or discarded if the Session is rolled back.
 * Blocking cache support has been added. Therefore any get() that returns a cache miss
 * will be followed by a put() so any lock associated with the key can be released.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class TransactionalCache implements Cache {

  private static final Log log = LogFactory.getLog(TransactionalCache.class);

  private final Cache delegate;
  /** 缓存清空标识 */
  private boolean clearOnCommit;
  private final Map<Object, Object> entriesToAddOnCommit;
  private final Set<Object> entriesMissedInCache;

  public TransactionalCache(Cache delegate) {
    this.delegate = delegate;
    this.clearOnCommit = false;
    this.entriesToAddOnCommit = new HashMap<>();
    this.entriesMissedInCache = new HashSet<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  @Override
  public Object getObject(Object key) {
    // issue #116
    // delegate 即为该暂存区指向的缓存空间
    Object object = delegate.getObject(key);
    if (object == null) {
      // 如果没有命中的情况下，也会添加个空值，用来防止缓存穿透
      entriesMissedInCache.add(key);
    }
    // issue #146
    // 如果暂存区被清空，那么clearOnCommit标识的值则为true，详见本类中clear()方法，暂存区被清空的情况下，就没有所谓缓存的数据了，哪怕二级缓存空间里面有，也不会给它返回
    // 这里有个问题，二级缓存空间里面有值，但是clearOnCommit=true，还是返回null呢？
    // 这就保证一个会话修改操作在没提交commit之前，另一个会话就不会去缓存中取数据
    if (clearOnCommit) {
      return null;
    } else {
      return object;
    }
  }

  @Override
  public void putObject(Object key, Object object) {
    // 填充到暂存区
    entriesToAddOnCommit.put(key, object);
  }

  @Override
  public Object removeObject(Object key) {
    return null;
  }

  @Override
  public void clear() {
    // 清空标识设置为true
    clearOnCommit = true;
    // 仅仅清空暂存区
    entriesToAddOnCommit.clear();
  }

  public void commit() {
    // 结合clear方法看，只有在清空标识为true的时候，真正去清空二级缓存
    if (clearOnCommit) {
      delegate.clear();
    }
    // 刷新等待的实体
    flushPendingEntries();
    reset();
  }

  public void rollback() {
    unlockMissedEntries();
    reset();
  }

  private void reset() {
    clearOnCommit = false;
    entriesToAddOnCommit.clear();
    entriesMissedInCache.clear();
  }

  /**
   * 刷新缓冲区实体
   * */
  private void flushPendingEntries() {
    // 遍历缓存区对象
    for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
      // 设置到二级缓存空间
      delegate.putObject(entry.getKey(), entry.getValue());
    }
    for (Object entry : entriesMissedInCache) {
      if (!entriesToAddOnCommit.containsKey(entry)) {
        delegate.putObject(entry, null);
      }
    }
  }

  private void unlockMissedEntries() {
    for (Object entry : entriesMissedInCache) {
      try {
        delegate.removeObject(entry);
      } catch (Exception e) {
        log.warn("Unexpected exception while notifying a rollback to the cache adapter. "
            + "Consider upgrading your cache adapter to the latest version. Cause: " + e);
      }
    }
  }

}

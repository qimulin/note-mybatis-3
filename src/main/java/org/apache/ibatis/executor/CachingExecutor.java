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
package org.apache.ibatis.executor;

import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.TransactionalCacheManager;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * 本执行器只专注于实现二级缓存相关的逻辑，
 * 原来的那些获取连接，查询数据库，采用了一个装饰者模式，用delegate【美[ˈdelɪɡət , ˈdelɪɡeɪt] 授权，代表】属性，该属性指向下一个Executor
 * 也就是说CachingExecutor逻辑执行完，则会交给装饰的delegate执行器去执行
 * 装饰者模式：在不改变原有类结构和继承的情况下，通过包装原对象去扩展一个新功能
 * 注意：二级缓存需要调用{@link #commit(boolean)} 参数true，才会保存到缓存中
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class CachingExecutor implements Executor {

  private final Executor delegate;
  private final TransactionalCacheManager tcm = new TransactionalCacheManager();
  /**
   * 相当于是为delegate添加一层二级缓存处理的装饰
   * */
  public CachingExecutor(Executor delegate) {
    this.delegate = delegate;
    delegate.setExecutorWrapper(this);
  }

  @Override
  public Transaction getTransaction() {
    return delegate.getTransaction();
  }

  @Override
  public void close(boolean forceRollback) {
    try {
      // issues #499, #524 and #573
      if (forceRollback) {
        tcm.rollback();
      } else {
        tcm.commit();
      }
    } finally {
      delegate.close(forceRollback);
    }
  }

  @Override
  public boolean isClosed() {
    return delegate.isClosed();
  }

  @Override
  public int update(MappedStatement ms, Object parameterObject) throws SQLException {
    // 所以其实可以通过设置flushCacheRequired=false控制修改操作不清空暂存区
    flushCacheIfRequired(ms);
    return delegate.update(ms, parameterObject);
  }

  @Override
  public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
    flushCacheIfRequired(ms);
    return delegate.queryCursor(ms, parameter, rowBounds);
  }

  @Override
  public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
    BoundSql boundSql = ms.getBoundSql(parameterObject);
    CacheKey key = createCacheKey(ms, parameterObject, rowBounds, boundSql);
    return query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
  }

  @Override
  public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql)
      throws SQLException {
    // 获取Cache根据两个参数相关，一个是是否配置了全局的缓存开关；第二个是是否有声明缓存空间
    // 如果声明了，则cache是不会等于null
    Cache cache = ms.getCache();
    if (cache != null) {
      // 查询前按需要清空二级缓存
      flushCacheIfRequired(ms);
      // MappedStatement使用缓存，并且resultHandler需要为null，即不能对结果集进行自定义处理
      if (ms.isUseCache() && resultHandler == null) {
        ensureNoOutParams(ms, boundSql);
        // 先走二级缓存查询：从缓存事务管理器获取缓存，这里之所以要传cache参数，是因为事务缓存管理器中会有许多暂存区，所以需要传入对应的缓存定义对象，才能找到对应的缓存
        @SuppressWarnings("unchecked")
        List<E> list = (List<E>) tcm.getObject(cache, key);
        if (list == null) {
          // 查不到，再走一级缓存
          list = delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
          // 查到的结果，填充到二级缓存暂存区，只有调commit方法后才会真正提交到二级缓存空间
          tcm.putObject(cache, key, list); // issue #578 and #116
        }
        return list;
      }
    }
    return delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
  }

  @Override
  public List<BatchResult> flushStatements() throws SQLException {
    return delegate.flushStatements();
  }

  /**
   * 直接交给下装饰执行器
   * */
  @Override
  public void commit(boolean required) throws SQLException {
    delegate.commit(required);
    tcm.commit();
  }

  /**
   * 直接交给下装饰执行器，只有和二级缓存相关的时候才会加上操作
   * */
  @Override
  public void rollback(boolean required) throws SQLException {
    try {
      delegate.rollback(required);
    } finally {
      if (required) {
        tcm.rollback();
      }
    }
  }

  private void ensureNoOutParams(MappedStatement ms, BoundSql boundSql) {
    if (ms.getStatementType() == StatementType.CALLABLE) {
      for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
        if (parameterMapping.getMode() != ParameterMode.IN) {
          throw new ExecutorException("Caching stored procedures with OUT params is not supported.  Please configure useCache=false in " + ms.getId() + " statement.");
        }
      }
    }
  }

  @Override
  public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
    return delegate.createCacheKey(ms, parameterObject, rowBounds, boundSql);
  }

  @Override
  public boolean isCached(MappedStatement ms, CacheKey key) {
    return delegate.isCached(ms, key);
  }

  @Override
  public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
    delegate.deferLoad(ms, resultObject, property, key, targetType);
  }

  @Override
  public void clearLocalCache() {
    delegate.clearLocalCache();
  }

  private void flushCacheIfRequired(MappedStatement ms) {
    Cache cache = ms.getCache();
    // 如果缓存不为null，且MappedStatement配置了flushCacheRequired=true
    if (cache != null && ms.isFlushCacheRequired()) {
      // 此处的情况只是情况暂存区，因为我们可以看到tcm对象类型是TransactionalCacheManager
      tcm.clear(cache);
    }
  }

  @Override
  public void setExecutorWrapper(Executor executor) {
    throw new UnsupportedOperationException("This method should not be called");
  }

}

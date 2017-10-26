/*
 *      Copyright (C) 2015 The Helenus Authors
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package net.helenus.core.operation;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.codahale.metrics.Timer;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.google.common.base.Function;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import net.helenus.core.AbstractSessionOperations;
import net.helenus.core.UnitOfWork;
import net.helenus.core.cache.CacheUtil;
import net.helenus.core.cache.Facet;

import static net.helenus.core.HelenusSession.deleted;

public abstract class AbstractOptionalOperation<E, O extends AbstractOptionalOperation<E, O>>
		extends
			AbstractStatementOperation<E, O> {

	public AbstractOptionalOperation(AbstractSessionOperations sessionOperations) {
		super(sessionOperations);
	}

	public abstract Optional<E> transform(ResultSet resultSet);

	public PreparedOptionalOperation<E> prepare() {
		return new PreparedOptionalOperation<E>(prepareStatement(), this);
	}

	public ListenableFuture<PreparedOptionalOperation<E>> prepareAsync() {
		final O _this = (O) this;
		return Futures.transform(prepareStatementAsync(),
				new Function<PreparedStatement, PreparedOptionalOperation<E>>() {
					@Override
					public PreparedOptionalOperation<E> apply(PreparedStatement preparedStatement) {
						return new PreparedOptionalOperation<E>(preparedStatement, _this);
					}
				});
	}

	public Optional<E> sync() {// throws TimeoutException {
		final Timer.Context context = requestLatency.time();
		try {
			Optional<E> result = Optional.empty();
			E cacheResult = null;
			boolean updateCache = isSessionCacheable();

			if (enableCache && isSessionCacheable()) {
				List<Facet> facets = bindFacetValues();
				String tableName = CacheUtil.schemaName(facets);
				cacheResult = (E) sessionOps.checkCache(tableName, facets);
				if (cacheResult != null) {
					result = Optional.of(cacheResult);
					updateCache = false;
					sessionCacheHits.mark();
					cacheHits.mark();
				} else {
					sessionCacheMiss.mark();
					cacheMiss.mark();
				}
			}

			if (!result.isPresent()) {
				// Formulate the query and execute it against the Cassandra cluster.
				ResultSet resultSet = this.execute(sessionOps, null, traceContext, queryExecutionTimeout,
						queryTimeoutUnits, showValues, false);

				// Transform the query result set into the desired shape.
				result = transform(resultSet);
			}

			if (updateCache && result.isPresent()) {
				List<Facet> facets = getFacets();
				if (facets != null && facets.size() > 1) {
					sessionOps.updateCache(result.get(), facets);
				}
			}
			return result;
		} finally {
			context.stop();
		}
	}

	public Optional<E> sync(UnitOfWork<?> uow) {// throws TimeoutException {
		if (uow == null)
			return sync();

		final Timer.Context context = requestLatency.time();
		try {

			Optional<E> result = Optional.empty();
			E cachedResult = null;
			final boolean updateCache;

			if (enableCache) {
				Stopwatch timer = Stopwatch.createStarted();
				try {
					List<Facet> facets = bindFacetValues();
					if (facets != null) {
                        cachedResult = checkCache(uow, facets);
                        if (cachedResult != null) {
                            updateCache = false;
                            result = Optional.of(cachedResult);
                            uowCacheHits.mark();
                            cacheHits.mark();
                            uow.recordCacheAndDatabaseOperationCount(1, 0);
                        } else {
                            updateCache = true;
                            uowCacheMiss.mark();
                            if (isSessionCacheable()) {
                                String tableName = CacheUtil.schemaName(facets);
                                cachedResult = (E) sessionOps.checkCache(tableName, facets);
                                if (cachedResult != null) {
                                    result = Optional.of(cachedResult);
                                    sessionCacheHits.mark();
                                    cacheHits.mark();
                                    uow.recordCacheAndDatabaseOperationCount(1, 0);
                                } else {
                                    sessionCacheMiss.mark();
                                    cacheMiss.mark();
                                    uow.recordCacheAndDatabaseOperationCount(-1, 0);
                                }
                            }
                        }
                    } else {
					    updateCache = false;
                    }
				} finally {
					timer.stop();
					uow.addCacheLookupTime(timer);
				}
			} else {
			    updateCache = false;
            }

			if (!result.isPresent()) {
				// Formulate the query and execute it against the Cassandra cluster.
				ResultSet resultSet = execute(sessionOps, uow, traceContext, queryExecutionTimeout, queryTimeoutUnits,
						showValues, true);

				// Transform the query result set into the desired shape.
				result = transform(resultSet);
			}

            if (result.get() == deleted) {
                return Optional.empty();
            } else {
                // If we have a result, it wasn't from the UOW cache, and we're caching things
                // then we need to put this result into the cache for future requests to find.
                if (updateCache && result.isPresent()) {
                    cacheUpdate(uow, result.get(), getFacets());
                }
                return result;
            }
		} finally {
			context.stop();
		}
	}

	public CompletableFuture<Optional<E>> async() {
		return CompletableFuture.<Optional<E>>supplyAsync(() -> {
			// try {
			return sync();
			// } catch (TimeoutException ex) {
			// throw new CompletionException(ex);
			// }
		});
	}

	public CompletableFuture<Optional<E>> async(UnitOfWork<?> uow) {
		if (uow == null)
			return async();
		return CompletableFuture.<Optional<E>>supplyAsync(() -> {
			// try {
			return sync();
			// } catch (TimeoutException ex) {
			// throw new CompletionException(ex);
			// }
		});
	}
}

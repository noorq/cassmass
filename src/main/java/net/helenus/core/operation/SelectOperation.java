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

import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.querybuilder.BuiltStatement;
import com.datastax.driver.core.querybuilder.Ordering;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import com.datastax.driver.core.querybuilder.Select.Selection;
import com.datastax.driver.core.querybuilder.Select.Where;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.helenus.core.*;
import net.helenus.core.reflect.HelenusPropertyNode;
import net.helenus.mapping.HelenusEntity;
import net.helenus.mapping.MappingUtil;
import net.helenus.mapping.OrderingDirection;
import net.helenus.mapping.value.ColumnValueProvider;
import net.helenus.mapping.value.ValueProviderMap;
import net.helenus.support.Fun;
import net.helenus.support.HelenusMappingException;

public final class SelectOperation<E> extends AbstractFilterStreamOperation<E, SelectOperation<E>> {

  protected Function<Row, E> rowMapper = null;
  protected final List<HelenusPropertyNode> props = new ArrayList<HelenusPropertyNode>();

  protected List<Ordering> ordering = null;
  protected Integer limit = null;
  protected boolean allowFiltering = false;

  protected CacheManager cacheManager;

  public SelectOperation(AbstractSessionOperations sessionOperations) {
    super(sessionOperations);

    this.rowMapper =
            new Function<Row, E>() {

              @Override
              public E apply(Row source) {

                ColumnValueProvider valueProvider = sessionOps.getValueProvider();
                Object[] arr = new Object[props.size()];

                int i = 0;
                for (HelenusPropertyNode p : props) {
                  Object value = valueProvider.getColumnValue(source, -1, p.getProperty());
                  arr[i++] = value;
                }

                return (E) Fun.ArrayTuple.of(arr);
              }
            };

    this.cacheManager = CacheManager.of(CacheManager.Type.FETCH, null) ;
  }

  public SelectOperation(AbstractSessionOperations sessionOperations, HelenusEntity entity) {

    super(sessionOperations);

    entity
            .getOrderedProperties()
            .stream()
            .map(p -> new HelenusPropertyNode(p, Optional.empty()))
            .forEach(p -> this.props.add(p));

    this.cacheManager = CacheManager.of(CacheManager.Type.FETCH, entity) ;
  }

  public SelectOperation(
          AbstractSessionOperations sessionOperations,
          HelenusEntity entity,
          Function<Row, E> rowMapper) {

    super(sessionOperations);
    this.rowMapper = rowMapper;

    entity
            .getOrderedProperties()
            .stream()
            .map(p -> new HelenusPropertyNode(p, Optional.empty()))
            .forEach(p -> this.props.add(p));

    this.cacheManager = CacheManager.of(CacheManager.Type.FETCH, entity) ;
  }

  public SelectOperation(
          AbstractSessionOperations sessionOperations,
          Function<Row, E> rowMapper,
          HelenusPropertyNode... props) {

    super(sessionOperations);
    this.rowMapper = rowMapper;
    Collections.addAll(this.props, props);

    this.cacheManager = CacheManager.of(CacheManager.Type.FETCH, null) ;
  }

  public CountOperation count() {

    HelenusEntity entity = null;
    for (HelenusPropertyNode prop : props) {

      if (entity == null) {
        entity = prop.getEntity();
      } else if (entity != prop.getEntity()) {
        throw new HelenusMappingException(
                "you can count records only from a single entity "
                        + entity.getMappingInterface()
                        + " or "
                        + prop.getEntity().getMappingInterface());
      }
    }

    return new CountOperation(sessionOps, entity);
  }

  public SelectFirstOperation<E> single() {
    limit(1);
    return new SelectFirstOperation<E>(this);
  }

  public <R> SelectTransformingOperation<R, E> mapTo(Class<R> entityClass) {

    Objects.requireNonNull(entityClass, "entityClass is null");

    HelenusEntity entity = Helenus.entity(entityClass);

    this.rowMapper = null;

    return new SelectTransformingOperation<R, E>(
            this,
            (r) -> {
              Map<String, Object> map = new ValueProviderMap(r, sessionOps.getValueProvider(), entity);
              return (R) Helenus.map(entityClass, map);
            });
  }

  public <R> SelectTransformingOperation<R, E> map(Function<E, R> fn) {
    return new SelectTransformingOperation<R, E>(this, fn);
  }

  public SelectOperation<E> column(Getter<?> getter) {
    HelenusPropertyNode p = MappingUtil.resolveMappingProperty(getter);
    this.props.add(p);
    return this;
  }

  public SelectOperation<E> orderBy(Getter<?> getter, OrderingDirection direction) {
    getOrCreateOrdering().add(new Ordered(getter, direction).getOrdering());
    return this;
  }

  public SelectOperation<E> orderBy(Ordered ordered) {
    getOrCreateOrdering().add(ordered.getOrdering());
    return this;
  }

  public SelectOperation<E> limit(Integer limit) {
    this.limit = limit;
    return this;
  }

  public SelectOperation<E> allowFiltering() {
    this.allowFiltering = true;
    return this;
  }

  @Override
  public BuiltStatement buildStatement() {

    HelenusEntity entity = null;
    Selection selection = QueryBuilder.select();

    for (HelenusPropertyNode prop : props) {
      selection = selection.column(prop.getColumnName());

      if (prop.getProperty().caseSensitiveIndex()) {
        allowFiltering = true;
      }

      if (entity == null) {
        entity = prop.getEntity();
      } else if (entity != prop.getEntity()) {
        throw new HelenusMappingException(
                "you can select columns only from a single entity "
                        + entity.getMappingInterface()
                        + " or "
                        + prop.getEntity().getMappingInterface());
      }
    }

    if (entity == null) {
      throw new HelenusMappingException("no entity or table to select data");
    }

    Select select = selection.from(entity.getName().toCql());

    if (ordering != null && !ordering.isEmpty()) {
      select.orderBy(ordering.toArray(new Ordering[ordering.size()]));
    }

    if (limit != null) {
      select.limit(limit);
    }

    if (filters != null && !filters.isEmpty()) {

      Where where = select.where();

      for (Filter<?> filter : filters) {
        where.and(filter.getClause(sessionOps.getValuePreparer()));
      }
    }

    if (ifFilters != null && !ifFilters.isEmpty()) {
      logger.error(
              "onlyIf conditions " + ifFilters + " would be ignored in the statement " + select);
    }

    if (allowFiltering) {
      select.allowFiltering();
    }

    return select;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Stream<E> transform(ResultSet resultSet) {

    if (rowMapper != null) {

      return StreamSupport.stream(
              Spliterators.spliteratorUnknownSize(resultSet.iterator(), Spliterator.ORDERED), false)
              .map(rowMapper);
    } else {

      return (Stream<E>)
              StreamSupport.stream(
                      Spliterators.spliteratorUnknownSize(resultSet.iterator(), Spliterator.ORDERED),
                      false);
    }
  }

  protected CacheManager getCacheManager() {
    return cacheManager;
  }

  private List<Ordering> getOrCreateOrdering() {
    if (ordering == null) {
      ordering = new ArrayList<Ordering>();
    }
    return ordering;
  }
}

/*
 *      Copyright (C) 2015 Noorq, Inc.
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
package com.noorq.casser.core;

import java.util.Objects;

import com.datastax.driver.core.querybuilder.Clause;
import com.noorq.casser.core.reflect.CasserPropertyNode;
import com.noorq.casser.mapping.CasserMappingProperty;
import com.noorq.casser.mapping.MappingUtil;
import com.noorq.casser.mapping.value.ColumnValuePreparer;
import com.noorq.casser.support.CasserMappingException;

public final class Filter<V> {

	private final CasserMappingProperty property;
	private final Postulate<V> postulate;
	
	private Filter(CasserMappingProperty prop, Operator op, V value) {
		this.property = prop;
		this.postulate = new Postulate<V>(op, value);
	}

	private Filter(CasserMappingProperty prop, Operator op, V[] values) {
		this.property = prop;
		this.postulate = new Postulate<V>(op, values);
	}

	private Filter(CasserMappingProperty prop, Postulate<V> postulate) {
		this.property = prop;
		this.postulate = postulate;
	}
	
	public CasserMappingProperty getProperty() {
		return property;
	}

	public Clause getClause(ColumnValuePreparer valuePreparer) {
		return postulate.getClause(property, valuePreparer);
	}
	
	public static <V> Filter<V> equal(Getter<V> getter, V val) {
		return create(getter, Operator.EQ, val);
	}

	public static <V> Filter<V> in(Getter<V> getter, V[] vals) {
		Objects.requireNonNull(getter, "empty getter");
		Objects.requireNonNull(vals, "empty values");
		
		if (vals.length == 0) {
			throw new IllegalArgumentException("values array is empty");
		}
		
		for (int i = 0; i != vals.length; ++i) {
			Objects.requireNonNull(vals[i], "value[" + i + "] is empty");
		}
		
		CasserPropertyNode prop = MappingUtil.resolveMappingProperty(getter);
		
		return new Filter<V>(prop.getProperty(), Operator.IN, vals);
	}
	
	public static <V> Filter<V> greaterThan(Getter<V> getter, V val) {
		return create(getter, Operator.GT, val);
	}
	
	public static <V> Filter<V> lessThan(Getter<V> getter, V val) {
		return create(getter, Operator.LT, val);
	}

	public static <V> Filter<V> greaterThanOrEqual(Getter<V> getter, V val) {
		return create(getter, Operator.GTE, val);
	}

	public static <V> Filter<V> lessThanOrEqual(Getter<V> getter, V val) {
		return create(getter, Operator.LTE, val);
	}

	public static <V> Filter<V> create(Getter<V> getter, Postulate<V> postulate) {
		Objects.requireNonNull(getter, "empty getter");
		Objects.requireNonNull(postulate, "empty operator");

		CasserPropertyNode prop = MappingUtil.resolveMappingProperty(getter);
		
		return new Filter<V>(prop.getProperty(), postulate);
	}
	
	public static <V> Filter<V> create(Getter<V> getter, String operator, V val) {
		Objects.requireNonNull(operator, "empty operator");
		
		Operator fo = Operator.findByOperator(operator);
		
		if (fo == null) {
			throw new CasserMappingException("invalid operator " + operator);
		}
		
		return create(getter, fo, val);
	}
	
	public static <V> Filter<V> create(Getter<V> getter, Operator op, V val) {
		Objects.requireNonNull(getter, "empty getter");
		Objects.requireNonNull(op, "empty op");
		Objects.requireNonNull(val, "empty value");
		
		if (op == Operator.IN) {
			throw new IllegalArgumentException("invalid usage of the 'in' operator, use Filter.in() static method");
		}
		
		CasserPropertyNode prop = MappingUtil.resolveMappingProperty(getter);
		
		return new Filter<V>(prop.getProperty(), op, val);
	}
	
}

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
package com.noorq.casser.test.integration.core.usertype;

import com.datastax.driver.core.UDTValue;
import com.noorq.casser.mapping.annotation.column.Column;
import com.noorq.casser.mapping.annotation.column.PartitionKey;
import com.noorq.casser.mapping.annotation.entity.Table;
import com.noorq.casser.mapping.annotation.type.UDT;

@Table
public interface Account {

	@PartitionKey(ordinal=0)
	long id();
	
	@Column
	Address address();
	
	@UDT("address0")
	@Column
	UDTValue addressNoMapping();
	
}

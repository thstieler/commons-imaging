/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sanselan.formats.jpeg.iptc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.apache.sanselan.ImageReadException;
import org.apache.sanselan.ImageWriteException;
import org.apache.sanselan.SanselanConstants;
import org.apache.sanselan.common.BinaryFileParser;
import org.apache.sanselan.common.BinaryOutputStream;
import org.apache.sanselan.util.Debug;
import org.apache.sanselan.util.ParamMap;

public class PhotoshopApp13Data implements IPTCConstants
{
	private final List records;
	private final List rawBlocks;

	public PhotoshopApp13Data(List records, List rawBlocks)
	{
		this.rawBlocks = rawBlocks;
		this.records = records;
	}

	public List getRecords()
	{
		return new ArrayList(records);
	}

	public List getRawBlocks()
	{
		return new ArrayList(rawBlocks);
	}

	public List getNonIptcBlocks()
	{
		List result = new ArrayList();
		for (int i = 0; i < rawBlocks.size(); i++)
		{
			IPTCBlock block = (IPTCBlock) rawBlocks.get(i);
			if (!block.isIPTCBlock())
				result.add(block);
		}
		return result;
	}

}

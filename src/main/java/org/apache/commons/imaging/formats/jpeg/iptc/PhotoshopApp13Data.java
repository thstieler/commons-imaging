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

package org.apache.commons.imaging.formats.jpeg.iptc;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PhotoshopApp13Data {
    private final List<IptcRecord> records;
    private final List<IptcBlock> rawBlocks;
    private final Charset forcedCharset;

    public PhotoshopApp13Data(final List<IptcRecord> records, final List<IptcBlock> rawBlocks, final Charset forcedCharset) {
        this.rawBlocks = rawBlocks == null ? Collections.emptyList() : Collections.unmodifiableList(rawBlocks);
        this.records = records == null ? Collections.emptyList() : Collections.unmodifiableList(records);
        this.forcedCharset = forcedCharset;
    }

    public PhotoshopApp13Data(final List<IptcRecord> records, final List<IptcBlock> rawBlocks) {
        this(records, rawBlocks, null);
    }

    public List<IptcBlock> getNonIptcBlocks() {
        final List<IptcBlock> result = new ArrayList<>();
        for (final IptcBlock block : rawBlocks) {
            if (!block.isIptcBlock()) {
                result.add(block);
            }
        }
        return result;
    }

    public List<IptcBlock> getRawBlocks() {
        return new ArrayList<>(rawBlocks);
    }

    public List<IptcRecord> getRecords() {
        return new ArrayList<>(records);
    }

    public Charset getForcedCharset() {
        return forcedCharset;
    }
}

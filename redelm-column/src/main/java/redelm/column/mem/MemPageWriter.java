/**
 * Copyright 2012 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package redelm.column.mem;

import static redelm.Log.DEBUG;

import java.util.ArrayList;
import java.util.List;

import redelm.Log;
import redelm.bytes.BytesInput;

public class MemPageWriter implements PageWriter {
  private static final Log LOG = Log.getLog(MemPageWriter.class);

  private final List<Page> pages = new ArrayList<Page>();
  private long memSize = 0;
  private int totalValueCount = 0;


  @Override
  public void writePage(BytesInput bytesInput, int valueCount) {
    memSize += bytesInput.size();
    pages.add(new Page(BytesInput.copy(bytesInput), valueCount));
    totalValueCount += valueCount;
    if (DEBUG) LOG.debug("page written for " + bytesInput.size() + " bytes and " + valueCount + " records");
  }

  @Override
  public long getMemSize() {
    return memSize;
  }

  public List<Page> getPages() {
    return pages;
  }

  public int getTotalValueCount() {
    return totalValueCount;
  }

}

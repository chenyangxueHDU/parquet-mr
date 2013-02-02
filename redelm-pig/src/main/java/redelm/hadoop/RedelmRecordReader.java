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
package redelm.hadoop;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import redelm.Log;
import redelm.bytes.BytesInput;
import redelm.column.mem.MemColumnsStore;
import redelm.column.mem.MemPageStore;
import redelm.column.mem.PageWriter;
import redelm.hadoop.metadata.BlockMetaData;
import redelm.hadoop.metadata.ColumnChunkMetaData;
import redelm.io.ColumnIOFactory;
import redelm.io.MessageColumnIO;
import redelm.parser.MessageTypeParser;
import redelm.schema.GroupType;
import redelm.schema.MessageType;
import redelm.schema.Type;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

/**
 * Reads the records from a block of a RedElm file
 *
 * @see RedelmInputFormat
 *
 * @author Julien Le Dem
 *
 * @param <T> type of the materialized records
 */
public class RedelmRecordReader<T> extends RecordReader<Void, T> {
  private static final Log LOG = Log.getLog(RedelmRecordReader.class);

  private ColumnIOFactory columnIOFactory = new ColumnIOFactory();
  private Map<String, ColumnChunkMetaData> columnTypes = new HashMap<String, ColumnChunkMetaData>();
  private T currentValue;
  private long total;
  private int current;
  private RedelmFileReader reader;
  private redelm.io.RecordReader<T> recordReader;
  private MemColumnsStore columnsStore;
  private ReadSupport<T> readSupport;
  private MessageType requestedSchema;

  private long totalTimeSpentReadingBytes;
  private long totalTimeSpentProcessingRecords;

  private long startedReadingCurrentBlockAt;
  private long currentBlockRecordCount;

  /**
   *
   * @param requestedSchema the requested schema (a subset of the original schema) for record projection
   */
  RedelmRecordReader(String requestedSchema) {
    this.requestedSchema = MessageTypeParser.parseMessageType(requestedSchema);
  }

  private void checkRead() throws IOException {
    if (columnsStore == null || current == total) {
      if (columnsStore != null) {
        long timeAssembling = System.currentTimeMillis() - startedReadingCurrentBlockAt;
        totalTimeSpentProcessingRecords += timeAssembling;
        LOG.info("Assembled and processed " + currentBlockRecordCount + " records in " + timeAssembling + " ms "+((float)currentBlockRecordCount / timeAssembling) + " rec/ms");
        long totalTime = totalTimeSpentProcessingRecords + totalTimeSpentReadingBytes;
        long percentReading = 100 * totalTimeSpentReadingBytes / totalTime;
        long percentProcessing = 100 * totalTimeSpentProcessingRecords / totalTime;
        LOG.info("time spent so far " + percentReading + "% reading ("+totalTimeSpentReadingBytes+" ms) and " + percentProcessing + "% processing ("+totalTimeSpentProcessingRecords+" ms)");
      }
      final MemPageStore memPageStore = new MemPageStore();
      columnsStore = new MemColumnsStore(0, memPageStore, 8 * 1024); // TODO: parameterize this
      LOG.info("reading next block");
      long t0 = System.currentTimeMillis();
      long count = reader.readColumns(new PageConsumer() {
        public void consumePage(String[] path, int valueCount, InputStream is, int pageSize) {
          PageWriter pageWriter = memPageStore.getPageWriter(requestedSchema.getColumnDescription(path));
          pageWriter.writePage(BytesInput.from(is, pageSize), valueCount);
        }
      });
      if (count == 0) {
        return;
      }
      long timeSpentReading = System.currentTimeMillis() - t0;
      totalTimeSpentReadingBytes += timeSpentReading;
      LOG.info("block read in memory in " + timeSpentReading + " ms");
      MessageColumnIO columnIO = columnIOFactory.getColumnIO(requestedSchema);
      recordReader = columnIO.getRecordReader(columnsStore, readSupport.newRecordConsumer());
      startedReadingCurrentBlockAt = System.currentTimeMillis();
      currentBlockRecordCount = count;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() throws IOException {
    reader.close();
  }

  /**
   * always returns null
   */
  @Override
  public Void getCurrentKey() throws IOException, InterruptedException {
    return null;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public T getCurrentValue() throws IOException,
  InterruptedException {
    return currentValue;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public float getProgress() throws IOException, InterruptedException {
    return (float)current/total;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void initialize(InputSplit inputSplit, TaskAttemptContext taskAttemptContext)
      throws IOException, InterruptedException {
    Configuration configuration = taskAttemptContext.getConfiguration();
    @SuppressWarnings("unchecked") // I know
    RedelmInputSplit<T> redelmInputSplit = (RedelmInputSplit<T>)inputSplit;
    this.readSupport = redelmInputSplit.getReadSupport();
    Path path = redelmInputSplit.getPath();
    List<BlockMetaData> blocks = redelmInputSplit.getBlocks();
    List<String[]> columns = new ArrayList<String[]>();
    // a split has at least one block
    // all blocks have the same columns
    // TODO: just generate the column list from the schema
    for (ColumnChunkMetaData columnMetaData : blocks.get(0).getColumns()) {
      columnTypes.put(Arrays.toString(columnMetaData.getPath()), columnMetaData);
      if (contains(requestedSchema, columnMetaData.getPath())) {
        columns.add(columnMetaData.getPath());
      }
    }
    reader = new RedelmFileReader(configuration, path, blocks, columns);
    for (BlockMetaData block : blocks) {
      total += block.getRowCount();
    }

  }

  private boolean contains(GroupType requestedSchema, String[] path) {
    return contains(requestedSchema, path, 0);
  }

  private boolean contains(GroupType group, String[] path, int index) {
    if (index == path.length) {
      return false;
    }
    if (group.containsField(path[index])) {
      Type type = group.getType(path[index]);
      if (type.isPrimitive()) {
        return index + 1 == path.length;
      } else {
        return contains(type.asGroupType(), path, index + 1);
      }
    }
    return false;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean nextKeyValue() throws IOException, InterruptedException {
    checkRead();
    if (current<total) {
      currentValue = recordReader.read();
      current ++;
      return true;
    }
    return false;
  }
}
/**
 * Copyright 2010 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.regionserver;

import java.io.DataInput;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.SortedSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValue.KVComparator;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.HalfStoreFileReader;
import org.apache.hadoop.hbase.io.Reference;
import org.apache.hadoop.hbase.io.hfile.BlockCache;
import org.apache.hadoop.hbase.io.hfile.BlockType;
import org.apache.hadoop.hbase.io.hfile.Compression;
import org.apache.hadoop.hbase.io.hfile.HFile;
import org.apache.hadoop.hbase.io.hfile.HFileScanner;
import org.apache.hadoop.hbase.io.hfile.HFileWriterV1;
import org.apache.hadoop.hbase.io.hfile.HFileWriterV2;
import org.apache.hadoop.hbase.io.hfile.LruBlockCache;
import org.apache.hadoop.hbase.regionserver.metrics.SchemaConfigured;
import org.apache.hadoop.hbase.regionserver.metrics.SchemaMetrics;
import org.apache.hadoop.hbase.util.BloomFilter;
import org.apache.hadoop.hbase.util.BloomFilterFactory;
import org.apache.hadoop.hbase.util.BloomFilterWriter;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Writables;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.util.StringUtils;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;

/**
 * A Store data file.  Stores usually have one or more of these files.  They
 * are produced by flushing the memstore to disk.  To
 * create, call {@link #createWriter(FileSystem, Path, int, Configuration)}
 * and append data. Be sure to add any metadata before calling close on the
 * Writer (Use the appendMetadata convenience methods). On close, a StoreFile
 * is sitting in the Filesystem.  To refer to it, create a StoreFile instance
 * passing filesystem and path.  To read, call {@link #createReader()}.
 * <p>StoreFiles may also reference store files in another Store.
 *
 * The reason for this weird pattern where you use a different instance for the
 * writer and a reader is that we write once but read a lot more.
 */
public class StoreFile {
  static final Log LOG = LogFactory.getLog(StoreFile.class.getName());

  static final String HFILE_BLOCK_CACHE_SIZE_KEY = "hfile.block.cache.size";

  public static enum BloomType {
    /**
     * Bloomfilters disabled
     */
    NONE,
    /**
     * Bloom enabled with Table row as Key
     */
    ROW,
    /**
     * Bloom enabled with Table row & column (family+qualifier) as Key
     */
    ROWCOL
  }

  // Keys for fileinfo values in HFile

  /** Max Sequence ID in FileInfo */
  public static final byte [] MAX_SEQ_ID_KEY = Bytes.toBytes("MAX_SEQ_ID_KEY");

  /** Major compaction flag in FileInfo */
  public static final byte[] MAJOR_COMPACTION_KEY =
      Bytes.toBytes("MAJOR_COMPACTION_KEY");

  /** Bloom filter Type in FileInfo */
  static final byte[] BLOOM_FILTER_TYPE_KEY =
      Bytes.toBytes("BLOOM_FILTER_TYPE");

  /** Delete Family Count in FileInfo */
  public static final byte[] DELETE_FAMILY_COUNT =
      Bytes.toBytes("DELETE_FAMILY_COUNT");

  /** Last Bloom filter key in FileInfo */
  private static final byte[] LAST_BLOOM_KEY = Bytes.toBytes("LAST_BLOOM_KEY");

  /** Key for Timerange information in metadata*/
  public static final byte[] TIMERANGE_KEY = Bytes.toBytes("TIMERANGE");

  // Make default block size for StoreFiles 8k while testing.  TODO: FIX!
  // Need to make it 8k for testing.
  public static final int DEFAULT_BLOCKSIZE_SMALL = 8 * 1024;


  private static BlockCache hfileBlockCache = null;

  private final FileSystem fs;

  // This file's path.
  private final Path path;

  // If this storefile references another, this is the reference instance.
  private Reference reference;

  // If this StoreFile references another, this is the other files path.
  private Path referencePath;

  // Should the block cache be used or not.
  private boolean blockcache;

  // Is this from an in-memory store
  private boolean inMemory;

  // Keys for metadata stored in backing HFile.
  // Set when we obtain a Reader.
  private long sequenceid = -1;

  // max of the MemstoreTS in the KV's in this store
  // Set when we obtain a Reader.
  private long maxMemstoreTS = -1;

  public long getMaxMemstoreTS() {
    return maxMemstoreTS;
  }

  public void setMaxMemstoreTS(long maxMemstoreTS) {
    this.maxMemstoreTS = maxMemstoreTS;
  }

  // If true, this file was product of a major compaction.  Its then set
  // whenever you get a Reader.
  private AtomicBoolean majorCompaction = null;

  /** Meta key set when store file is a result of a bulk load */
  public static final byte[] BULKLOAD_TASK_KEY =
    Bytes.toBytes("BULKLOAD_SOURCE_TASK");
  public static final byte[] BULKLOAD_TIME_KEY =
    Bytes.toBytes("BULKLOAD_TIMESTAMP");

  /**
   * Map of the metadata entries in the corresponding HFile
   */
  private Map<byte[], byte[]> metadataMap;

  /*
   * Regex that will work for straight filenames and for reference names.
   * If reference, then the regex has more than just one group.  Group 1 is
   * this file's id.  Group 2 the referenced region name, etc.
   */
  private static final Pattern REF_NAME_PARSER =
    Pattern.compile("^([0-9a-f]+)(?:\\.(.+))?$");

  // StoreFile.Reader
  private volatile Reader reader;

  private final Configuration conf;

  /**
   * Bloom filter type specified in column family configuration. Does not
   * necessarily correspond to the Bloom filter type present in the HFile.
   */
  private final BloomType cfBloomType;

  // the last modification time stamp
  private long modificationTimeStamp = 0L;

  /**
   * Constructor, loads a reader and it's indices, etc. May allocate a
   * substantial amount of ram depending on the underlying files (10-20MB?).
   *
   * @param fs  The current file system to use.
   * @param p  The path of the file.
   * @param blockcache  <code>true</code> if the block cache is enabled.
   * @param conf  The current configuration.
   * @param cfBloomType The bloom type to use for this store file as specified
   *          by column family configuration. This may or may not be the same
   *          as the Bloom filter type actually present in the HFile, because
   *          column family configuration might change. If this is
   *          {@link BloomType#NONE}, the existing Bloom filter is ignored.
   * @throws IOException When opening the reader fails.
   */
  StoreFile(final FileSystem fs,
            final Path p,
            final boolean blockcache,
            final Configuration conf,
            final BloomType cfBloomType,
            final boolean inMemory)
      throws IOException {
    this.conf = conf;
    this.fs = fs;
    this.path = p;
    this.blockcache = blockcache;
    this.inMemory = inMemory;
    if (isReference(p)) {
      this.reference = Reference.read(fs, p);
      this.referencePath = getReferredToFile(this.path);
    }

    if (BloomFilterFactory.isGeneralBloomEnabled(conf)) {
      this.cfBloomType = cfBloomType;
    } else {
      LOG.info("Ignoring bloom filter check for file " + path + ": " +
          "cfBloomType=" + cfBloomType + " (disabled in config)");
      this.cfBloomType = BloomType.NONE;
    }

    // cache the modification time stamp of this store file
    FileStatus[] stats = fs.listStatus(p);
    if (stats != null && stats.length == 1) {
      this.modificationTimeStamp = stats[0].getModificationTime();
    } else {
      this.modificationTimeStamp = 0;
    }
  }

  /**
   * @return Path or null if this StoreFile was made with a Stream.
   */
  Path getPath() {
    return this.path;
  }

  /**
   * @return The Store/ColumnFamily this file belongs to.
   */
  byte [] getFamily() {
    return Bytes.toBytes(this.path.getParent().getName());
  }

  /**
   * @return True if this is a StoreFile Reference; call after {@link #open()}
   * else may get wrong answer.
   */
  boolean isReference() {
    return this.reference != null;
  }

  /**
   * @param p Path to check.
   * @return True if the path has format of a HStoreFile reference.
   */
  public static boolean isReference(final Path p) {
    return !p.getName().startsWith("_") &&
      isReference(p, REF_NAME_PARSER.matcher(p.getName()));
  }

  /**
   * @param p Path to check.
   * @param m Matcher to use.
   * @return True if the path has format of a HStoreFile reference.
   */
  public static boolean isReference(final Path p, final Matcher m) {
    if (m == null || !m.matches()) {
      LOG.warn("Failed match of store file name " + p.toString());
      throw new RuntimeException("Failed match of store file name " +
          p.toString());
    }
    return m.groupCount() > 1 && m.group(2) != null;
  }

  /*
   * Return path to the file referred to by a Reference.  Presumes a directory
   * hierarchy of <code>${hbase.rootdir}/tablename/regionname/familyname</code>.
   * @param p Path to a Reference file.
   * @return Calculated path to parent region file.
   * @throws IOException
   */
  static Path getReferredToFile(final Path p) {
    Matcher m = REF_NAME_PARSER.matcher(p.getName());
    if (m == null || !m.matches()) {
      LOG.warn("Failed match of store file name " + p.toString());
      throw new RuntimeException("Failed match of store file name " +
          p.toString());
    }
    // Other region name is suffix on the passed Reference file name
    String otherRegion = m.group(2);
    // Tabledir is up two directories from where Reference was written.
    Path tableDir = p.getParent().getParent().getParent();
    String nameStrippedOfSuffix = m.group(1);
    // Build up new path with the referenced region in place of our current
    // region in the reference path.  Also strip regionname suffix from name.
    return new Path(new Path(new Path(tableDir, otherRegion),
      p.getParent().getName()), nameStrippedOfSuffix);
  }

  /**
   * @return True if this file was made by a major compaction.
   */
  boolean isMajorCompaction() {
    if (this.majorCompaction == null) {
      throw new NullPointerException("This has not been set yet");
    }
    return this.majorCompaction.get();
  }

  /**
   * @return This files maximum edit sequence id.
   */
  public long getMaxSequenceId() {
    return this.sequenceid;
  }

  public long getModificationTimeStamp() {
    return modificationTimeStamp;
  }

  public void setModificationTimeStamp(long modificationTimeStamp) {
    this.modificationTimeStamp = modificationTimeStamp;
  }

  /**
   * Return the largest memstoreTS found across all storefiles in
   * the given list. Store files that were created by a mapreduce
   * bulk load are ignored, as they do not correspond to any specific
   * put operation, and thus do not have a memstoreTS associated with them.
   * @return 0 if no non-bulk-load files are provided or, this is Store that
   * does not yet have any store files.
   */
  public static long getMaxMemstoreTSInList(Collection<StoreFile> sfs) {
    long max = 0;
    for (StoreFile sf : sfs) {
      if (!sf.isBulkLoadResult()) {
        max = Math.max(max, sf.getMaxMemstoreTS());
      }
    }
    return max;
  }

  /**
   * Return the highest sequence ID found across all storefiles in
   * the given list. Store files that were created by a mapreduce
   * bulk load are ignored, as they do not correspond to any edit
   * log items.
   * @return 0 if no non-bulk-load files are provided or, this is Store that
   * does not yet have any store files.
   */
  public static long getMaxSequenceIdInList(Collection<StoreFile> sfs) {
    long max = 0;
    for (StoreFile sf : sfs) {
      if (!sf.isBulkLoadResult()) {
        max = Math.max(max, sf.getMaxSequenceId());
      }
    }
    return max;
  }

  /**
   * @return true if this storefile was created by HFileOutputFormat
   * for a bulk load.
   */
  boolean isBulkLoadResult() {
    return metadataMap.containsKey(BULKLOAD_TIME_KEY);
  }

  /**
   * Return the timestamp at which this bulk load file was generated.
   */
  public long getBulkLoadTimestamp() {
    return Bytes.toLong(metadataMap.get(BULKLOAD_TIME_KEY));
  }

  /**
   * Returns the block cache or <code>null</code> in case none should be used.
   *
   * @param conf  The current configuration.
   * @return The block cache or <code>null</code>.
   */
  public static synchronized BlockCache getBlockCache(Configuration conf) {
    if (hfileBlockCache != null) return hfileBlockCache;

    float cachePercentage = conf.getFloat(HFILE_BLOCK_CACHE_SIZE_KEY, 0.0f);
    // There should be a better way to optimize this. But oh well.
    if (cachePercentage == 0L) return null;
    if (cachePercentage > 1.0) {
      throw new IllegalArgumentException(HFILE_BLOCK_CACHE_SIZE_KEY +
        " must be between 0.0 and 1.0, not > 1.0");
    }

    // Calculate the amount of heap to give the heap.
    MemoryUsage mu = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    long cacheSize = (long)(mu.getMax() * cachePercentage);
    LOG.info("Allocating LruBlockCache with maximum size " +
      StringUtils.humanReadableInt(cacheSize));
    hfileBlockCache = new LruBlockCache(cacheSize, DEFAULT_BLOCKSIZE_SMALL,
        conf);
    return hfileBlockCache;
  }

  /**
   * @return the blockcache
   */
  public BlockCache getBlockCache() {
    return blockcache ? getBlockCache(conf) : null;
  }

  /**
   * Opens reader on this store file.  Called by Constructor.
   * @return Reader for the store file.
   * @throws IOException
   * @see #closeReader()
   */
  private Reader open() throws IOException {

    if (this.reader != null) {
      throw new IllegalAccessError("Already open");
    }

    if (isReference()) {
      this.reader = new HalfStoreFileReader(this.fs, this.referencePath,
          getBlockCache(), this.reference);
    } else {
      SchemaMetrics.configureGlobally(conf);
      this.reader = new Reader(this.fs, this.path, getBlockCache(),
          this.inMemory,
          this.conf.getBoolean(HFile.EVICT_BLOCKS_ON_CLOSE_KEY, true));
    }

    // Load up indices and fileinfo. This also loads Bloom filter type.
    metadataMap = Collections.unmodifiableMap(this.reader.loadFileInfo());

    // Read in our metadata.
    byte [] b = metadataMap.get(MAX_SEQ_ID_KEY);
    if (b != null) {
      // By convention, if halfhfile, top half has a sequence number > bottom
      // half. Thats why we add one in below. Its done for case the two halves
      // are ever merged back together --rare.  Without it, on open of store,
      // since store files are distinguished by sequence id, the one half would
      // subsume the other.
      this.sequenceid = Bytes.toLong(b);
      if (isReference()) {
        if (Reference.isTopFileRegion(this.reference.getFileRegion())) {
          this.sequenceid += 1;
        }
      }
    }
    this.reader.setSequenceID(this.sequenceid);

    b = metadataMap.get(HFileWriterV2.MAX_MEMSTORE_TS_KEY);
    if (b != null) {
      this.maxMemstoreTS = Bytes.toLong(b);
    }

    b = metadataMap.get(MAJOR_COMPACTION_KEY);
    if (b != null) {
      boolean mc = Bytes.toBoolean(b);
      if (this.majorCompaction == null) {
        this.majorCompaction = new AtomicBoolean(mc);
      } else {
        this.majorCompaction.set(mc);
      }
    }

    BloomType hfileBloomType = reader.getBloomFilterType();
    if (cfBloomType != BloomType.NONE) {
      reader.loadBloomfilter(BlockType.GENERAL_BLOOM_META);
      if (hfileBloomType != cfBloomType) {
        LOG.info("HFile Bloom filter type for "
            + reader.getHFileReader().getName() + ": " + hfileBloomType
            + ", but " + cfBloomType + " specified in column family "
            + reader.getColumnFamilyName());
      }
    } else if (hfileBloomType != BloomType.NONE) {
      LOG.info("Bloom filter turned off by CF config for "
          + reader.getHFileReader().getName());
    }

    // load delete family bloom filter
    reader.loadBloomfilter(BlockType.DELETE_FAMILY_BLOOM_META);

    try {
      byte [] timerangeBytes = metadataMap.get(TIMERANGE_KEY);
      if (timerangeBytes != null) {
        this.reader.timeRangeTracker = new TimeRangeTracker();
        Writables.copyWritable(timerangeBytes, this.reader.timeRangeTracker);
      }
    } catch (IllegalArgumentException e) {
      LOG.error("Error reading timestamp range data from meta -- " +
          "proceeding without", e);
      this.reader.timeRangeTracker = null;
    }
    return this.reader;
  }

  /**
   * @return Reader for StoreFile. creates if necessary
   * @throws IOException
   */
  public Reader createReader() throws IOException {
    if (this.reader == null) {
      this.reader = open();
    }
    return this.reader;
  }

  /**
   * @return Current reader.  Must call createReader first else returns null.
   * @throws IOException
   * @see {@link #createReader()}
   */
  public Reader getReader() {
    return this.reader;
  }

  /**
   * @throws IOException
   */
  public synchronized void closeReader() throws IOException {
    if (this.reader != null) {
      this.reader.close();
      this.reader = null;
    }
  }

  /**
   * Delete this file
   * @throws IOException
   */
  public void deleteReader() throws IOException {
    closeReader();
    this.fs.delete(getPath(), true);
  }

  @Override
  public String toString() {
    return this.path.toString() +
      (isReference()? "-" + this.referencePath + "-" + reference.toString(): "");
  }

  /**
   * @return a length description of this StoreFile, suitable for debug output
   */
  public String toStringDetailed() {
    StringBuilder sb = new StringBuilder();
    sb.append(this.path.toString());
    sb.append(", isReference=").append(isReference());
    sb.append(", isBulkLoadResult=").append(isBulkLoadResult());
    if (isBulkLoadResult()) {
      sb.append(", bulkLoadTS=").append(getBulkLoadTimestamp());
    } else {
      sb.append(", seqid=").append(getMaxSequenceId());
    }
    sb.append(", majorCompaction=").append(isMajorCompaction());

    return sb.toString();
  }

  /**
   * Utility to help with rename.
   * @param fs
   * @param src
   * @param tgt
   * @return True if succeeded.
   * @throws IOException
   */
  public static Path rename(final FileSystem fs,
                            final Path src,
                            final Path tgt)
      throws IOException {

    if (!fs.exists(src)) {
      throw new FileNotFoundException(src.toString());
    }
    if (!fs.rename(src, tgt)) {
      throw new IOException("Failed rename of " + src + " to " + tgt);
    }
    return tgt;
  }

  /**
   * Get a store file writer. Client is responsible for closing file when done.
   *
   * @param fs
   * @param dir Path to family directory.  Makes the directory if doesn't exist.
   * Creates a file with a unique name in this directory.
   * @param blocksize size per filesystem block
   * @return StoreFile.Writer
   * @throws IOException
   */
  public static Writer createWriter(final FileSystem fs, final Path dir,
      final int blocksize, Configuration conf) throws IOException {
    return createWriter(fs, dir, blocksize, null, null, conf, BloomType.NONE,
        0);
  }
  public static StoreFile.Writer createWriter(final FileSystem fs,
          final Path dir,
          final int blocksize,
          final Compression.Algorithm algorithm,
          final KeyValue.KVComparator c,
          final Configuration conf,
          BloomType bloomType,
          long maxKeyCount)
  throws IOException {
      return createWriter(fs, dir, blocksize, algorithm, c, conf, bloomType,
        BloomFilterFactory.getErrorRate(conf), maxKeyCount, null, true);
  }

  /**
   * Create a store file writer. Client is responsible for closing file when done.
   * If metadata, add BEFORE closing using appendMetadata()
   * @param fs
   * @param dir Path to family directory.  Makes the directory if doesn't exist.
   * Creates a file with a unique name in this directory.
   * @param blocksize
   * @param algorithm Pass null to get default.
   * @param c Pass null to get default.
   * @param conf HBase system configuration. used with bloom filters
   * @param bloomType column family setting for bloom filters
   * @param bloomErrorRate column family setting for bloom filter error rate
   * @param maxKeyCount estimated maximum number of keys we expect to add
   * @param favoredNodes if using DFS, try to place replicas on these nodes
   * @return HFile.Writer
   * @throws IOException
   */
  public static StoreFile.Writer createWriter(final FileSystem fs,
                                              final Path dir,
                                              final int blocksize,
                                              final Compression.Algorithm algorithm,
                                              final KeyValue.KVComparator c,
                                              final Configuration conf,
                                              BloomType bloomType,
                                              float bloomErrorRate,
                                              long maxKeyCount,
                                              InetSocketAddress[] favoredNodes,
                                              boolean allowCacheOnWrite)
      throws IOException {

    if (!fs.exists(dir)) {
      fs.mkdirs(dir);
    }
    Path path = getUniqueFile(fs, dir);
    if (!BloomFilterFactory.isGeneralBloomEnabled(conf)) {
      bloomType = BloomType.NONE;
    }

    return new Writer(fs, path, blocksize,
        algorithm == null? HFile.DEFAULT_COMPRESSION_ALGORITHM: algorithm,
        conf, c == null ? KeyValue.COMPARATOR: c, bloomType, bloomErrorRate,
        maxKeyCount, favoredNodes, allowCacheOnWrite);
  }

  /**
   * @param fs
   * @param dir Directory to create file in.
   * @return random filename inside passed <code>dir</code>
   */
  public static Path getUniqueFile(final FileSystem fs, final Path dir)
      throws IOException {
    if (!fs.getFileStatus(dir).isDir()) {
      throw new IOException("Expecting " + dir.toString() +
        " to be a directory");
    }
    return getRandomFilename(fs, dir);
  }

  /**
   *
   * @param fs
   * @param dir
   * @return Path to a file that doesn't exist at time of this invocation.
   * @throws IOException
   */
  static Path getRandomFilename(final FileSystem fs, final Path dir)
      throws IOException {
    return getRandomFilename(fs, dir, null);
  }

  /**
   *
   * @param fs
   * @param dir
   * @param suffix
   * @return Path to a file that doesn't exist at time of this invocation.
   * @throws IOException
   */
  static Path getRandomFilename(final FileSystem fs,
                                final Path dir,
                                final String suffix)
      throws IOException {
    return new Path(dir, UUID.randomUUID().toString().replaceAll("-", "")
        + ((suffix == null || suffix.length() <= 0) ? "" : suffix));
  }

  /**
   * Write out a split reference.
   *
   * Package local so it does not leak out of regionserver.
   *
   * @param fs
   * @param splitDir Presumes path format is actually
   * <code>SOME_DIRECTORY/REGIONNAME/FAMILY</code>.
   * @param f File to split.
   * @param splitRow
   * @param range
   * @return Path to created reference.
   * @throws IOException
   */
  static Path split(final FileSystem fs,
                    final Path splitDir,
                    final StoreFile f,
                    final byte [] splitRow,
                    final Reference.Range range)
      throws IOException {
    // A reference to the bottom half of the hsf store file.
    Reference r = new Reference(splitRow, range);
    // Add the referred-to regions name as a dot separated suffix.
    // See REF_NAME_PARSER regex above.  The referred-to regions name is
    // up in the path of the passed in <code>f</code> -- parentdir is family,
    // then the directory above is the region name.
    String parentRegionName = f.getPath().getParent().getParent().getName();
    // Write reference with same file id only with the other region name as
    // suffix and into the new region location (under same family).
    Path p = new Path(splitDir, f.getPath().getName() + "." + parentRegionName);
    return r.write(fs, p);
  }


  /**
   * A StoreFile writer.  Use this to read/write HBase Store Files. It is package
   * local because it is an implementation detail of the HBase regionserver.
   */
  public static class Writer {
    private final BloomFilterWriter generalBloomFilterWriter;
    private final BloomFilterWriter deleteFamilyBloomFilterWriter;
    private final BloomType bloomType;
    private byte[] lastBloomKey;
    private int lastBloomKeyOffset, lastBloomKeyLen;
    private KVComparator kvComparator;
    private KeyValue lastKv = null;
    private KeyValue lastDeleteFamilyKV = null;
    private long deleteFamilyCnt = 0;

    TimeRangeTracker timeRangeTracker = new TimeRangeTracker();
    /* isTimeRangeTrackerSet keeps track if the timeRange has already been set
     * When flushing a memstore, we set TimeRange and use this variable to
     * indicate that it doesn't need to be calculated again while
     * appending KeyValues.
     * It is not set in cases of compactions when it is recalculated using only
     * the appended KeyValues*/
    boolean isTimeRangeTrackerSet = false;

    protected HFile.Writer writer;

    public Writer(FileSystem fs, Path path, int blocksize,
            Compression.Algorithm compress, final Configuration conf,
            final KVComparator comparator, BloomType bloomType,  long maxKeys)
            throws IOException {
     this(fs, path, blocksize, compress, conf, comparator, bloomType,
          BloomFilterFactory.getErrorRate(conf), maxKeys, null, true);
    }

    public Writer(FileSystem fs, Path path, int blocksize,
        Compression.Algorithm compress, final Configuration conf,
        final KVComparator comparator, BloomType bloomType,
        float bloomErrorRate, long maxKeys) throws IOException {
      this(fs, path, blocksize, compress, conf, comparator, bloomType,
          bloomErrorRate, maxKeys, null, true);
    }

    /**
     * Creates an HFile.Writer that also write helpful meta data.
     * @param fs file system to write to
     * @param path file name to create
     * @param blocksize HDFS block size
     * @param compress HDFS block compression
     * @param conf user configuration
     * @param comparator key comparator
     * @param bloomType bloom filter setting
     * @param bloomErrorRate error rate for bloom filter
     * @param maxKeys the expected maximum number of keys to be added. Was used
     *        for Bloom filter size in {@link HFile} format version 1.
     * @param favoredNodes if using DFS, try to place replicas on these nodes
     * @throws IOException problem writing to FS
     */
    public Writer(FileSystem fs, Path path, int blocksize,
        Compression.Algorithm compress, final Configuration conf,
        final KVComparator comparator, BloomType bloomType,
        float bloomErrorRate, long maxKeys, InetSocketAddress[] favoredNodes,
        boolean allowCacheOnWrite) throws IOException {

      writer = HFile.getWriterFactory(conf).createWriter(
          fs, path, blocksize, HFile.getBytesPerChecksum(conf, fs.getConf()),
          compress, comparator.getRawComparator(), favoredNodes,
          allowCacheOnWrite);

      this.kvComparator = comparator;

      generalBloomFilterWriter = BloomFilterFactory.createGeneralBloomAtWrite(
          conf, bloomType, (int) Math.min(maxKeys, Integer.MAX_VALUE), writer,
          bloomErrorRate);

      if (generalBloomFilterWriter != null) {
        this.bloomType = bloomType;
        LOG.info("Bloom filter type for " + path + ": " + this.bloomType + ", "
            + generalBloomFilterWriter.getClass().getSimpleName());
      } else {
        // Not using Bloom filters.
        this.bloomType = BloomType.NONE;
      }

      // initialize delete family Bloom filter when there is NO RowCol Bloom
      // filter
      if (this.bloomType != BloomType.ROWCOL) {
        this.deleteFamilyBloomFilterWriter = BloomFilterFactory
            .createDeleteBloomAtWrite(conf,
                (int) Math.min(maxKeys, Integer.MAX_VALUE), writer,
                bloomErrorRate);
      } else {
        deleteFamilyBloomFilterWriter = null;
      }
      if (deleteFamilyBloomFilterWriter != null) {
        LOG.info("Delete Family Bloom filter type for " + path + ": "
            + deleteFamilyBloomFilterWriter.getClass().getSimpleName());
      }
    }

    /**
     * Writes meta data.
     * Call before {@link #close()} since its written as meta data to this file.
     * @param maxSequenceId Maximum sequence id.
     * @param majorCompaction True if this file is product of a major compaction
     * @throws IOException problem writing to FS
     */
    public void appendMetadata(final long maxSequenceId, final boolean majorCompaction)
    throws IOException {
      writer.appendFileInfo(MAX_SEQ_ID_KEY, Bytes.toBytes(maxSequenceId));
      writer.appendFileInfo(MAJOR_COMPACTION_KEY,
          Bytes.toBytes(majorCompaction));
      appendTimeRangeMetadata();
    }

    /**
     * Add TimestampRange to Metadata
     */
    public void appendTimeRangeMetadata() throws IOException {
      appendFileInfo(TIMERANGE_KEY,WritableUtils.toByteArray(timeRangeTracker));
    }

    /**
     * Set TimeRangeTracker
     * @param trt
     */
    public void setTimeRangeTracker(final TimeRangeTracker trt) {
      this.timeRangeTracker = trt;
      isTimeRangeTrackerSet = true;
    }

    /**
     * If the timeRangeTracker is not set,
     * update TimeRangeTracker to include the timestamp of this key
     * @param kv
     * @throws IOException
     */
    public void includeInTimeRangeTracker(final KeyValue kv) {
      if (!isTimeRangeTrackerSet) {
        timeRangeTracker.includeTimestamp(kv);
      }
    }

    /**
     * If the timeRangeTracker is not set,
     * update TimeRangeTracker to include the timestamp of this key
     * @param key
     * @throws IOException
     */
    public void includeInTimeRangeTracker(final byte [] key) {
      if (!isTimeRangeTrackerSet) {
        timeRangeTracker.includeTimestamp(key);
      }
    }

    private void appendGeneralBloomfilter(final KeyValue kv) throws IOException {
      if (this.generalBloomFilterWriter != null) {
        // only add to the bloom filter on a new, unique key
        boolean newKey = true;
        if (this.lastKv != null) {
          switch(bloomType) {
          case ROW:
            newKey = ! kvComparator.matchingRows(kv, lastKv);
            break;
          case ROWCOL:
            newKey = ! kvComparator.matchingRowColumn(kv, lastKv);
            break;
          case NONE:
            newKey = false;
            break;
          default:
            throw new IOException("Invalid Bloom filter type: " + bloomType);
          }
        }
        if (newKey) {
          /*
           * http://2.bp.blogspot.com/_Cib_A77V54U/StZMrzaKufI/AAAAAAAAADo/ZhK7bGoJdMQ/s400/KeyValue.png
           * Key = RowLen + Row + FamilyLen + Column [Family + Qualifier] + TimeStamp
           *
           * 2 Types of Filtering:
           *  1. Row = Row
           *  2. RowCol = Row + Qualifier
           */
          byte[] bloomKey;
          int bloomKeyOffset, bloomKeyLen;

          switch (bloomType) {
          case ROW:
            bloomKey = kv.getBuffer();
            bloomKeyOffset = kv.getRowOffset();
            bloomKeyLen = kv.getRowLength();
            break;
          case ROWCOL:
            // merge(row, qualifier)
            // TODO: could save one buffer copy in case of compound Bloom
            // filters when this involves creating a KeyValue
            bloomKey = generalBloomFilterWriter.createBloomKey(kv.getBuffer(),
                kv.getRowOffset(), kv.getRowLength(), kv.getBuffer(),
                kv.getQualifierOffset(), kv.getQualifierLength());
            bloomKeyOffset = 0;
            bloomKeyLen = bloomKey.length;
            break;
          default:
            throw new IOException("Invalid Bloom filter type: " + bloomType +
                " (ROW or ROWCOL expected)");
          }
          generalBloomFilterWriter.add(bloomKey, bloomKeyOffset, bloomKeyLen);
          if (lastBloomKey != null
              && generalBloomFilterWriter.getComparator().compare(bloomKey,
                  bloomKeyOffset, bloomKeyLen, lastBloomKey,
                  lastBloomKeyOffset, lastBloomKeyLen) <= 0) {
            throw new IOException("Non-increasing Bloom keys: "
                + Bytes.toStringBinary(bloomKey, bloomKeyOffset, bloomKeyLen)
                + " after "
                + Bytes.toStringBinary(lastBloomKey, lastBloomKeyOffset,
                    lastBloomKeyLen));
          }
          lastBloomKey = bloomKey;
          lastBloomKeyOffset = bloomKeyOffset;
          lastBloomKeyLen = bloomKeyLen;
          this.lastKv = kv;
        }
      }
    }

    private void appendDeleteFamilyBloomFilter(final KeyValue kv)
        throws IOException {
      if (!kv.isDeleteFamily()) {
        return;
      }

      // increase the number of delete family in the store file
      deleteFamilyCnt++;
      if (null != this.deleteFamilyBloomFilterWriter) {
        boolean newKey = true;
        if (lastDeleteFamilyKV != null) {
          newKey = !kvComparator.matchingRows(kv, lastDeleteFamilyKV);
        }
        if (newKey) {
          this.deleteFamilyBloomFilterWriter.add(kv.getBuffer(),
              kv.getRowOffset(), kv.getRowLength());
          this.lastDeleteFamilyKV = kv;
        }
      }
    }

    public void append(final KeyValue kv) throws IOException {
      appendGeneralBloomfilter(kv);
      appendDeleteFamilyBloomFilter(kv);
      writer.append(kv);
      includeInTimeRangeTracker(kv);
    }

    public Path getPath() {
      return this.writer.getPath();
    }

    boolean hasGeneralBloom() {
      return this.generalBloomFilterWriter != null;

    }

    /**
     * For unit testing only.
     *
     * @return the Bloom filter used by this writer.
     */
    BloomFilterWriter getGeneralBloomWriter() {
      return generalBloomFilterWriter;
    }

    private boolean closeBloomFilter(BloomFilterWriter bfw) throws IOException {
      boolean haveBloom = (bfw != null && bfw.getKeyCount() > 0);
      if (haveBloom) {
        bfw.compactBloom();
      }
      return haveBloom;
    }

    private boolean closeGeneralBloomFilter() throws IOException {
      boolean hasGeneralBloom = closeBloomFilter(generalBloomFilterWriter);

      // add the general Bloom filter writer and append file info
      if (hasGeneralBloom) {
        writer.addGeneralBloomFilter(generalBloomFilterWriter);
        writer.appendFileInfo(BLOOM_FILTER_TYPE_KEY,
            Bytes.toBytes(bloomType.toString()));
        if (lastBloomKey != null) {
          writer.appendFileInfo(LAST_BLOOM_KEY, Arrays.copyOfRange(
              lastBloomKey, lastBloomKeyOffset, lastBloomKeyOffset
                  + lastBloomKeyLen));
        }
      }
      return hasGeneralBloom;
    }

    private boolean closeDeleteFamilyBloomFilter() throws IOException {
      boolean hasDeleteFamilyBloom = closeBloomFilter(deleteFamilyBloomFilterWriter);

      // add the delete family Bloom filter writer
      if (hasDeleteFamilyBloom) {
        writer.addDeleteFamilyBloomFilter(deleteFamilyBloomFilterWriter);
      }

      // append file info about the number of delete family kvs
      // even if there is no delete family Bloom.
      writer.appendFileInfo(DELETE_FAMILY_COUNT,
          Bytes.toBytes(this.deleteFamilyCnt));

      return hasDeleteFamilyBloom;
    }

    public void close() throws IOException {
      boolean hasGeneralBloom = this.closeGeneralBloomFilter();
      boolean hasDeleteFamilyBloom = this.closeDeleteFamilyBloomFilter();

      writer.close();

      // Log final Bloom filter statistics. This needs to be done after close()
      // because compound Bloom filters might be finalized as part of closing.
      StoreFile.LOG.info((hasGeneralBloom ? "" : "NO ") + "General Bloom and "
          + (hasDeleteFamilyBloom ? "" : "NO ") + "DeleteFamily"
          + " was added to HFile (" + getPath() + ") ");

    }

    public void appendFileInfo(byte[] key, byte[] value) throws IOException {
      writer.appendFileInfo(key, value);
    }

    /** For use in testing, e.g. {@link CreateRandomStoreFile} */
    HFile.Writer getHFileWriter() {
      return writer;
    }
  }

  /**
   * Reader for a StoreFile.
   */
  public static class Reader extends SchemaConfigured {
    static final Log LOG = LogFactory.getLog(Reader.class.getName());

    protected BloomFilter generalBloomFilter = null;
    protected BloomFilter deleteFamilyBloomFilter = null;
    protected BloomType bloomFilterType;
    private final HFile.Reader reader;
    protected TimeRangeTracker timeRangeTracker = null;
    protected long sequenceID = -1;
    private byte[] lastBloomKey;
    private long deleteFamilyCnt = -1;

    private Reader(HFile.Reader reader) {
      super(reader);
      this.reader = reader;
    }

    public Reader(FileSystem fs, Path path, BlockCache blockCache,
        boolean inMemory, boolean evictOnClose)
        throws IOException {
      this(HFile.createReader(fs, path, blockCache, inMemory,
          evictOnClose));
      bloomFilterType = BloomType.NONE;
    }

    /**
     * ONLY USE DEFAULT CONSTRUCTOR FOR UNIT TESTS
     */
    Reader() {
      this.reader = null;
    }

    public RawComparator<byte []> getComparator() {
      return reader.getComparator();
    }

    /**
     * Get a scanner to scan over this StoreFile. Do not use
     * this overload if using this scanner for compactions.
     *
     * @param cacheBlocks should this scanner cache blocks?
     * @param pread use pread (for highly concurrent small readers)
     * @return a scanner
     */
    public StoreFileScanner getStoreFileScanner(boolean cacheBlocks,
                                               boolean pread) {
      return getStoreFileScanner(cacheBlocks, pread, false);
    }

    /**
     * Get a scanner to scan over this StoreFile.
     *
     * @param cacheBlocks should this scanner cache blocks?
     * @param pread use pread (for highly concurrent small readers)
     * @param isCompaction is scanner being used for compaction?
     * @return a scanner
     */
    public StoreFileScanner getStoreFileScanner(boolean cacheBlocks,
                                               boolean pread,
                                               boolean isCompaction) {
      return new StoreFileScanner(this,
                                 getScanner(cacheBlocks, pread,
                                            isCompaction), !isCompaction);
    }

    /**
     * Warning: Do not write further code which depends on this call. Instead
     * use getStoreFileScanner() which uses the StoreFileScanner class/interface
     * which is the preferred way to scan a store with higher level concepts.
     *
     * @param cacheBlocks should we cache the blocks?
     * @param pread use pread (for concurrent small readers)
     * @return the underlying HFileScanner
     */
    @Deprecated
    public HFileScanner getScanner(boolean cacheBlocks, boolean pread){
      return getScanner(cacheBlocks, pread, false);
    }

    /**
     * Warning: Do not write further code which depends on this call. Instead
     * use getStoreFileScanner() which uses the StoreFileScanner class/interface
     * which is the preferred way to scan a store with higher level concepts.
     *
     * @param cacheBlocks should we cache the blocks?
     * @param pread use pread (for concurrent small readers)
     * @param isCompaction is scanner being used for compaction?
     * @return the underlying HFileScanner
     */
    @Deprecated
    public HFileScanner getScanner(boolean cacheBlocks, boolean pread,
                                  boolean isCompaction) {
      return reader.getScanner(cacheBlocks, pread, isCompaction);
    }

    public void close() throws IOException {
      reader.close();
    }

    /**
     * Check if this storeFile may contain keys within the TimeRange.
     * @param scan the current scan
     * @param oldestUnexpiredTS the oldest timestamp that is not expired, as
     *          determined by the column family's TTL
     * @return false if queried keys definitely don't exist in this StoreFile
     */
    boolean passesTimerangeFilter(Scan scan, long oldestUnexpiredTS) {
      if (timeRangeTracker == null) {
        return true;
      } else {
        return timeRangeTracker.includesTimeRange(scan.getTimeRange()) &&
            timeRangeTracker.getMaximumTimestamp() >= oldestUnexpiredTS;
      }
    }

    /**
     * Checks whether the given scan passes the Bloom filter (if present). Only
     * checks Bloom filters for single-row or single-row-column scans. Bloom
     * filter checking for multi-gets is implemented as part of the store
     * scanner system (see {@link StoreFileScanner#seekExactly}) and uses
     * the lower-level API {@link #passesGeneralBloomFilter(byte[], int, int, byte[],
     * int, int)}.
     *
     * @param scan the scan specification. Used to determine the row, and to
     *          check whether this is a single-row ("get") scan.
     * @param columns the set of columns. Only used for row-column Bloom
     *          filters.
     * @return true if the scan with the given column set passes the Bloom
     *         filter, or if the Bloom filter is not applicable for the scan.
     *         False if the Bloom filter is applicable and the scan fails it.
     */
    boolean passesBloomFilter(Scan scan, final SortedSet<byte[]> columns) {
      if (!scan.isGetScan())
        return true;

      byte[] row = scan.getStartRow();
      switch (this.bloomFilterType) {
        case ROW:
          return passesGeneralBloomFilter(row, 0, row.length, null, 0, 0);

        case ROWCOL:
          if (columns != null && columns.size() == 1) {
            byte[] column = columns.first();
            return passesGeneralBloomFilter(row, 0, row.length, column, 0,
                column.length);
          }

          // For multi-column queries the Bloom filter is checked from the
          // seekExact operation.
          return true;

        default:
          return true;
      }
    }

    public boolean passesDeleteFamilyBloomFilter(byte[] row, int rowOffset,
        int rowLen) {
      // Cache Bloom filter as a local variable in case it is set to null by
      // another thread on an IO error.
      BloomFilter bloomFilter = this.deleteFamilyBloomFilter;

      // Empty file or there is no delete family at all
      if (reader.getTrailer().getEntryCount() == 0 || deleteFamilyCnt == 0) {
        return false;
      }

      if (bloomFilter == null) {
        return true;
      }

      try {
        if (!bloomFilter.supportsAutoLoading()) {
          return true;
        }
        return bloomFilter.contains(row, rowOffset, rowLen, null);
      } catch (IllegalArgumentException e) {
        LOG.error("Bad Delete Family bloom filter data -- proceeding without",
            e);
        setDeleteFamilyBloomFilterFaulty();
      }

      return true;
    }

    /**
     * A method for checking Bloom filters. Called directly from
     * {@link StoreFileScanner} in case of a multi-column query.
     *
     * @param row
     * @param rowOffset
     * @param rowLen
     * @param col
     * @param colOffset
     * @param colLen
     * @return
     */
    public boolean passesGeneralBloomFilter(byte[] row, int rowOffset,
        int rowLen, byte[] col, int colOffset, int colLen) {
      if (generalBloomFilter == null)
        return true;

      byte[] key;
      switch (bloomFilterType) {
        case ROW:
          if (col != null) {
            throw new RuntimeException("Row-only Bloom filter called with " +
                "column specified");
          }
          if (rowOffset != 0 || rowLen != row.length) {
              throw new AssertionError("For row-only Bloom filters the row "
                  + "must occupy the whole array");
          }
          key = row;
          break;

        case ROWCOL:
          key = generalBloomFilter.createBloomKey(row, rowOffset, rowLen, col,
              colOffset, colLen);
          break;

        default:
          return true;
      }

      // Cache Bloom filter as a local variable in case it is set to null by
      // another thread on an IO error.
      BloomFilter bloomFilter = this.generalBloomFilter;

      if (bloomFilter == null) {
        return true;
      }

      // Empty file
      if (reader.getTrailer().getEntryCount() == 0)
        return false;

      try {
        boolean shouldCheckBloom;
        ByteBuffer bloom;
        if (bloomFilter.supportsAutoLoading()) {
          bloom = null;
          shouldCheckBloom = true;
        } else {
          bloom = reader.getMetaBlock(HFileWriterV1.BLOOM_FILTER_DATA_KEY,
              true);
          shouldCheckBloom = bloom != null;
        }

        if (shouldCheckBloom) {
          boolean exists;

          // Whether the primary Bloom key is greater than the last Bloom key
          // from the file info. For row-column Bloom filters this is not yet
          // a sufficient condition to return false.
          boolean keyIsAfterLast = lastBloomKey != null
              && bloomFilter.getComparator().compare(key, lastBloomKey) > 0;

          if (bloomFilterType == BloomType.ROWCOL) {
            // Since a Row Delete is essentially a DeleteFamily applied to all
            // columns, a file might be skipped if using row+col Bloom filter.
            // In order to ensure this file is included an additional check is
            // required looking only for a row bloom.
            byte[] rowBloomKey = bloomFilter.createBloomKey(row, 0, row.length,
                null, 0, 0);

            if (keyIsAfterLast
                && bloomFilter.getComparator().compare(rowBloomKey,
                    lastBloomKey) > 0) {
              exists = false;
            } else {
              exists =
                  bloomFilter.contains(key, 0, key.length, bloom) ||
                  bloomFilter.contains(rowBloomKey, 0, rowBloomKey.length,
                      bloom);
            }
          } else {
            exists = !keyIsAfterLast
                && bloomFilter.contains(key, 0, key.length, bloom);
          }

          getSchemaMetrics().updateBloomMetrics(exists);
          return exists;
        }
      } catch (IOException e) {
        LOG.error("Error reading bloom filter data -- proceeding without",
            e);
        setGeneralBloomFilterFaulty();
      } catch (IllegalArgumentException e) {
        LOG.error("Bad bloom filter data -- proceeding without", e);
        setGeneralBloomFilterFaulty();
      }

      return true;
    }

    public Map<byte[], byte[]> loadFileInfo() throws IOException {
      Map<byte [], byte []> fi = reader.loadFileInfo();

      byte[] b = fi.get(BLOOM_FILTER_TYPE_KEY);
      if (b != null) {
        bloomFilterType = BloomType.valueOf(Bytes.toString(b));
      }

      lastBloomKey = fi.get(LAST_BLOOM_KEY);
      byte[] cnt = fi.get(DELETE_FAMILY_COUNT);
      if (cnt != null) {
        deleteFamilyCnt = Bytes.toLong(cnt);
      }

      return fi;
    }

    public void loadBloomfilter() {
      this.loadBloomfilter(BlockType.GENERAL_BLOOM_META);
      this.loadBloomfilter(BlockType.DELETE_FAMILY_BLOOM_META);
    }

    private void loadBloomfilter(BlockType blockType) {
      try {
        if (blockType == BlockType.GENERAL_BLOOM_META) {
          if (this.generalBloomFilter != null)
            return; // Bloom has been loaded

          DataInput bloomMeta = reader.getGeneralBloomFilterMetadata();
          if (bloomMeta != null) {
            // sanity check for NONE Bloom filter
            if (bloomFilterType == BloomType.NONE) {
              throw new IOException(
                  "valid bloom filter type not found in FileInfo");
            } else {
              generalBloomFilter = BloomFilterFactory.createFromMeta(bloomMeta,
                  reader);
              LOG.info("Loaded " + bloomFilterType.toString() + " ("
                  + generalBloomFilter.getClass().getSimpleName()
                  + ") metadata for " + reader.getName());
            }
          }
        } else if (blockType == BlockType.DELETE_FAMILY_BLOOM_META) {
          if (this.deleteFamilyBloomFilter != null)
            return; // Bloom has been loaded

          DataInput bloomMeta = reader.getDeleteBloomFilterMetadata();
          if (bloomMeta != null) {
            deleteFamilyBloomFilter = BloomFilterFactory.createFromMeta(
                bloomMeta, reader);
            LOG.info("Loaded Delete Family Bloom ("
                + deleteFamilyBloomFilter.getClass().getSimpleName()
                + ") metadata for " + reader.getName());
          }
        } else {
          throw new RuntimeException("Block Type: " + blockType.toString()
              + "is not supported for Bloom filter");
        }
      } catch (IOException e) {
        LOG.error("Error reading bloom filter meta for " + blockType
            + " -- proceeding without", e);
        setBloomFilterFaulty(blockType);
      } catch (IllegalArgumentException e) {
        LOG.error("Bad bloom filter meta " + blockType
            + " -- proceeding without", e);
        setBloomFilterFaulty(blockType);
      }
    }

    private void setBloomFilterFaulty(BlockType blockType) {
      if (blockType == BlockType.GENERAL_BLOOM_META) {
        setGeneralBloomFilterFaulty();
      } else if (blockType == BlockType.DELETE_FAMILY_BLOOM_META) {
        setDeleteFamilyBloomFilterFaulty();
      }
    }

    /**
     * The number of Bloom filter entries in this store file, or an estimate
     * thereof, if the Bloom filter is not loaded. This always returns an upper
     * bound of the number of Bloom filter entries.
     *
     * @return an estimate of the number of Bloom filter entries in this file
     */
    public long getFilterEntries() {
      return generalBloomFilter != null ? generalBloomFilter.getKeyCount()
          : reader.getEntries();
    }

    public void setGeneralBloomFilterFaulty() {
      generalBloomFilter = null;
    }

    public void setDeleteFamilyBloomFilterFaulty() {
      this.deleteFamilyBloomFilter = null;
    }

    public byte[] getLastKey() {
      return reader.getLastKey();
    }

    public byte[] midkey() throws IOException {
      return reader.midkey();
    }

    public long length() {
      return reader.length();
    }

    public long getEntries() {
      return reader.getEntries();
    }

    public long getDeleteFamilyCnt() {
      return deleteFamilyCnt;
    }

    public byte[] getFirstKey() {
      return reader.getFirstKey();
    }

    public long indexSize() {
      return reader.indexSize();
    }

    public String getColumnFamilyName() {
      return reader.getColumnFamilyName();
    }

    public BloomType getBloomFilterType() {
      return this.bloomFilterType;
    }

    public long getSequenceID() {
      return sequenceID;
    }

    public void setSequenceID(long sequenceID) {
      this.sequenceID = sequenceID;
    }

    BloomFilter getGeneralBloomFilter() {
      return generalBloomFilter;
    }

    long getUncompressedDataIndexSize() {
      return reader.getTrailer().getUncompressedDataIndexSize();
    }

    public long getTotalBloomSize() {
      if (generalBloomFilter == null)
        return 0;
      return generalBloomFilter.getByteSize();
    }

    public int getHFileVersion() {
      return reader.getTrailer().getVersion();
    }

    HFile.Reader getHFileReader() {
      return reader;
    }

    void disableBloomFilterForTesting() {
      generalBloomFilter = null;
      this.deleteFamilyBloomFilter = null;
    }

    public long getMaxTimestamp() {
      return timeRangeTracker.maximumTimestamp;
    }
  }

  /**
   * Useful comparators for comparing StoreFiles.
   */
  abstract static class Comparators {
    /**
     * Comparator that compares based on the flush time of
     * the StoreFiles. All bulk loads are placed before all non-
     * bulk loads, and then all files are sorted by sequence ID.
     * If there are ties, the path name is used as a tie-breaker.
     */
    static final Comparator<StoreFile> FLUSH_TIME =
      Ordering.compound(ImmutableList.of(
          Ordering.natural().onResultOf(new GetBulkTime()),
          Ordering.natural().onResultOf(new GetSeqId()),
          Ordering.natural().onResultOf(new GetPathName())
      ));

    private static class GetBulkTime implements Function<StoreFile, Long> {
      @Override
      public Long apply(StoreFile sf) {
        if (!sf.isBulkLoadResult()) return Long.MAX_VALUE;
        return sf.getBulkLoadTimestamp();
      }
    }
    private static class GetSeqId implements Function<StoreFile, Long> {
      @Override
      public Long apply(StoreFile sf) {
        if (sf.isBulkLoadResult()) return -1L;
        return sf.getMaxSequenceId();
      }
    }
    private static class GetPathName implements Function<StoreFile, String> {
      @Override
      public String apply(StoreFile sf) {
        return sf.getPath().getName();
      }
    }

    /**
     * FILE_SIZE = descending sort StoreFiles (largest --> smallest in size)
     */
    static final Comparator<StoreFile> FILE_SIZE =
      Ordering.natural().reverse().onResultOf(new Function<StoreFile, Long>() {
        @Override
        public Long apply(StoreFile sf) {
          return sf.getReader().length();
        }
      });
  }

}

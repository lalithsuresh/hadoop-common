/**
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
package org.apache.hadoop.hdfs;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.PrivilegedExceptionAction;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.client.HdfsDataInputStream;
import org.apache.hadoop.hdfs.protocol.BlockLocalPathInfo;
import org.apache.hadoop.hdfs.protocol.ClientDatanodeProtocol;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.protocol.datatransfer.DataTransferProtocol;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenIdentifier;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.DataNodeTestUtils;
import org.apache.hadoop.hdfs.server.datanode.SimulatedFSDataset;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.Time;
import org.junit.Assert;
import org.junit.Test;

/**
 * Test for short circuit read functionality using {@link BlockReaderLocal}.
 * When a block is being read by a client is on the local datanode, instead of
 * using {@link DataTransferProtocol} and connect to datanode, the short circuit
 * read allows reading the file directly from the files on the local file
 * system.
 */
public class TestShortCircuitLocalRead {

  static final String DIR = "/" + TestShortCircuitLocalRead.class.getSimpleName() + "/";

  static final long seed = 0xDEADBEEFL;
  static final int blockSize = 5120;
  boolean simulatedStorage = false;
  
  // creates a file but does not close it
  static FSDataOutputStream createFile(FileSystem fileSys, Path name, int repl)
      throws IOException {
    FSDataOutputStream stm = fileSys.create(name, true,
                                            fileSys.getConf().getInt("io.file.buffer.size", 4096),
                                            (short)repl, blockSize);
    return stm;
  }

  static private void checkData(byte[] actual, int from, byte[] expected,
      String message) {
    checkData(actual, from, expected, actual.length, message);
  }
  
  static private void checkData(byte[] actual, int from, byte[] expected, int len,
      String message) {
    for (int idx = 0; idx < len; idx++) {
      if (expected[from + idx] != actual[idx]) {
        Assert.fail(message + " byte " + (from + idx) + " differs. expected "
            + expected[from + idx] + " actual " + actual[idx]);
      }
    }
  }
  
  private static String getCurrentUser() throws IOException {
    return UserGroupInformation.getCurrentUser().getShortUserName();
  }

  /** Check file content, reading as user {@code readingUser} */
  static void checkFileContent(URI uri, Path name, byte[] expected,
      int readOffset, String readingUser, Configuration conf,
      boolean shortCircuitFails)
      throws IOException, InterruptedException {
    // Ensure short circuit is enabled
    DistributedFileSystem fs = getFileSystem(readingUser, uri, conf);
    assertTrue(fs.getClient().getShortCircuitLocalReads());
    
    FSDataInputStream stm = fs.open(name);
    byte[] actual = new byte[expected.length-readOffset];
    stm.readFully(readOffset, actual);
    checkData(actual, readOffset, expected, "Read 2");
    stm.close();
    // Now read using a different API.
    actual = new byte[expected.length-readOffset];
    stm = fs.open(name);
    IOUtils.skipFully(stm, readOffset);
    //Read a small number of bytes first.
    int nread = stm.read(actual, 0, 3);
    nread += stm.read(actual, nread, 2);
    //Read across chunk boundary
    nread += stm.read(actual, nread, 517);
    checkData(actual, readOffset, expected, nread, "A few bytes");
    //Now read rest of it
    while (nread < actual.length) {
      int nbytes = stm.read(actual, nread, actual.length - nread);
      if (nbytes < 0) {
        throw new EOFException("End of file reached before reading fully.");
      }
      nread += nbytes;
    }
    checkData(actual, readOffset, expected, "Read 3");
    
    if (shortCircuitFails) {
      // short circuit should be disabled due to failure
      assertFalse(fs.getClient().getShortCircuitLocalReads());
    }
    stm.close();
  }

  private static byte [] arrayFromByteBuffer(ByteBuffer buf) {
    ByteBuffer alt = buf.duplicate();
    alt.clear();
    byte[] arr = new byte[alt.remaining()];
    alt.get(arr);
    return arr;
  }
  
  /** Check the file content, reading as user {@code readingUser} */
  static void checkFileContentDirect(URI uri, Path name, byte[] expected,
      int readOffset, String readingUser, Configuration conf,
      boolean shortCircuitFails)
      throws IOException, InterruptedException {
    // Ensure short circuit is enabled
    DistributedFileSystem fs = getFileSystem(readingUser, uri, conf);
    assertTrue(fs.getClient().getShortCircuitLocalReads());
    
    HdfsDataInputStream stm = (HdfsDataInputStream)fs.open(name);

    ByteBuffer actual = ByteBuffer.allocateDirect(expected.length - readOffset);

    IOUtils.skipFully(stm, readOffset);

    actual.limit(3);

    //Read a small number of bytes first.
    int nread = stm.read(actual);
    actual.limit(nread + 2);
    nread += stm.read(actual);

    // Read across chunk boundary
    actual.limit(Math.min(actual.capacity(), nread + 517));
    nread += stm.read(actual);
    checkData(arrayFromByteBuffer(actual), readOffset, expected, nread,
        "A few bytes");
    //Now read rest of it
    actual.limit(actual.capacity());
    while (actual.hasRemaining()) {
      int nbytes = stm.read(actual);

      if (nbytes < 0) {
        throw new EOFException("End of file reached before reading fully.");
      }
      nread += nbytes;
    }
    checkData(arrayFromByteBuffer(actual), readOffset, expected, "Read 3");
    if (shortCircuitFails) {
      // short circuit should be disabled due to failure
      assertFalse(fs.getClient().getShortCircuitLocalReads());
    }
    stm.close();
  }

  public void doTestShortCircuitRead(boolean ignoreChecksum, int size,
      int readOffset) throws IOException, InterruptedException {
    String shortCircuitUser = getCurrentUser();
    doTestShortCircuitRead(ignoreChecksum, size, readOffset, shortCircuitUser,
        shortCircuitUser, false);
  }
  
  /**
   * Test that file data can be read by reading the block file
   * directly from the local store.
   */
  public void doTestShortCircuitRead(boolean ignoreChecksum, int size,
      int readOffset, String shortCircuitUser, String readingUser,
      boolean shortCircuitFails) throws IOException, InterruptedException {
    Configuration conf = new Configuration();
    conf.setBoolean(DFSConfigKeys.DFS_CLIENT_READ_SHORTCIRCUIT_KEY, true);
    conf.setBoolean(DFSConfigKeys.DFS_CLIENT_READ_SHORTCIRCUIT_SKIP_CHECKSUM_KEY,
        ignoreChecksum);
    conf.set(DFSConfigKeys.DFS_BLOCK_LOCAL_PATH_ACCESS_USER_KEY,
        shortCircuitUser);
    if (simulatedStorage) {
      SimulatedFSDataset.setFactory(conf);
    }
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1)
        .format(true).build();
    FileSystem fs = cluster.getFileSystem();
    try {
      // check that / exists
      Path path = new Path("/");
      assertTrue("/ should be a directory", fs.getFileStatus(path)
          .isDirectory() == true);
      
      // create a new file in home directory. Do not close it.
      byte[] fileData = AppendTestUtil.randomBytes(seed, size);
      Path file1 = fs.makeQualified(new Path("filelocal.dat"));
      FSDataOutputStream stm = createFile(fs, file1, 1);
      stm.write(fileData);
      stm.close();
      
      URI uri = cluster.getURI();
      checkFileContent(uri, file1, fileData, readOffset, readingUser, conf,
          shortCircuitFails);
      checkFileContentDirect(uri, file1, fileData, readOffset, readingUser,
          conf, shortCircuitFails);
    } finally {
      fs.close();
      cluster.shutdown();
    }
  }

  @Test(timeout=10000)
  public void testFileLocalReadNoChecksum() throws Exception {
    doTestShortCircuitRead(true, 3*blockSize+100, 0);
  }

  @Test(timeout=10000)
  public void testFileLocalReadChecksum() throws Exception {
    doTestShortCircuitRead(false, 3*blockSize+100, 0);
  }
  
  @Test(timeout=10000)
  public void testSmallFileLocalRead() throws Exception {
    doTestShortCircuitRead(false, 13, 0);
    doTestShortCircuitRead(false, 13, 5);
    doTestShortCircuitRead(true, 13, 0);
    doTestShortCircuitRead(true, 13, 5);
  }
  
  /**
   * Try a short circuit from a reader that is not allowed to
   * to use short circuit. The test ensures reader falls back to non
   * shortcircuit reads when shortcircuit is disallowed.
   */
  @Test(timeout=10000)
  public void testLocalReadFallback() throws Exception {
    doTestShortCircuitRead(true, 13, 0, getCurrentUser(), "notallowed", true);
  }
  
  @Test(timeout=10000)
  public void testReadFromAnOffset() throws Exception {
    doTestShortCircuitRead(false, 3*blockSize+100, 777);
    doTestShortCircuitRead(true, 3*blockSize+100, 777);
  }
  
  @Test(timeout=10000)
  public void testLongFile() throws Exception {
    doTestShortCircuitRead(false, 10*blockSize+100, 777);
    doTestShortCircuitRead(true, 10*blockSize+100, 777);
  }
   
  private ClientDatanodeProtocol getProxy(UserGroupInformation ugi,
      final DatanodeID dnInfo, final Configuration conf) throws IOException,
      InterruptedException {
    return ugi.doAs(new PrivilegedExceptionAction<ClientDatanodeProtocol>() {
      @Override
      public ClientDatanodeProtocol run() throws Exception {
        return DFSUtil.createClientDatanodeProtocolProxy(dnInfo, conf, 60000,
            false);
      }
    });
  }
  
  private static DistributedFileSystem getFileSystem(String user, final URI uri,
      final Configuration conf) throws InterruptedException, IOException {
    UserGroupInformation ugi = UserGroupInformation.createRemoteUser(user);
    return ugi.doAs(new PrivilegedExceptionAction<DistributedFileSystem>() {
      @Override
      public DistributedFileSystem run() throws Exception {
        return (DistributedFileSystem)FileSystem.get(uri, conf);
      }
    });
  }
  
  @Test(timeout=10000)
  public void testGetBlockLocalPathInfo() throws IOException, InterruptedException {
    final Configuration conf = new Configuration();
    conf.set(DFSConfigKeys.DFS_BLOCK_LOCAL_PATH_ACCESS_USER_KEY,
        "alloweduser1,alloweduser2");
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1)
        .format(true).build();
    cluster.waitActive();
    final DataNode dn = cluster.getDataNodes().get(0);
    FileSystem fs = cluster.getFileSystem();
    try {
      DFSTestUtil.createFile(fs, new Path("/tmp/x"), 16, (short) 1, 23);
      UserGroupInformation aUgi1 =
          UserGroupInformation.createRemoteUser("alloweduser1");
      UserGroupInformation aUgi2 =
          UserGroupInformation.createRemoteUser("alloweduser2");
      LocatedBlocks lb = cluster.getNameNode().getRpcServer()
          .getBlockLocations("/tmp/x", 0, 16);
      // Create a new block object, because the block inside LocatedBlock at
      // namenode is of type BlockInfo.
      ExtendedBlock blk = new ExtendedBlock(lb.get(0).getBlock());
      Token<BlockTokenIdentifier> token = lb.get(0).getBlockToken();
      final DatanodeInfo dnInfo = lb.get(0).getLocations()[0];
      ClientDatanodeProtocol proxy = getProxy(aUgi1, dnInfo, conf);
      // This should succeed
      BlockLocalPathInfo blpi = proxy.getBlockLocalPathInfo(blk, token);
      Assert.assertEquals(
          DataNodeTestUtils.getFSDataset(dn).getBlockLocalPathInfo(blk).getBlockPath(),
          blpi.getBlockPath());

      // Try with the other allowed user
      proxy = getProxy(aUgi2, dnInfo, conf);

      // This should succeed as well
      blpi = proxy.getBlockLocalPathInfo(blk, token);
      Assert.assertEquals(
          DataNodeTestUtils.getFSDataset(dn).getBlockLocalPathInfo(blk).getBlockPath(),
          blpi.getBlockPath());

      // Now try with a disallowed user
      UserGroupInformation bUgi = UserGroupInformation
          .createRemoteUser("notalloweduser");
      proxy = getProxy(bUgi, dnInfo, conf);
      try {
        proxy.getBlockLocalPathInfo(blk, token);
        Assert.fail("The call should have failed as " + bUgi.getShortUserName()
            + " is not allowed to call getBlockLocalPathInfo");
      } catch (IOException ex) {
        Assert.assertTrue(ex.getMessage().contains(
            "not allowed to call getBlockLocalPathInfo"));
      }
    } finally {
      fs.close();
      cluster.shutdown();
    }
  }

  @Test(timeout=10000)
  public void testSkipWithVerifyChecksum() throws IOException {
    int size = blockSize;
    Configuration conf = new Configuration();
    conf.setBoolean(DFSConfigKeys.DFS_CLIENT_READ_SHORTCIRCUIT_KEY, true);
    conf.setBoolean(DFSConfigKeys.DFS_CLIENT_READ_SHORTCIRCUIT_SKIP_CHECKSUM_KEY, false);
    conf.set(DFSConfigKeys.DFS_BLOCK_LOCAL_PATH_ACCESS_USER_KEY,
        getCurrentUser());
    if (simulatedStorage) {
      SimulatedFSDataset.setFactory(conf);
    }
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1)
        .format(true).build();
    FileSystem fs = cluster.getFileSystem();
    try {
      // check that / exists
      Path path = new Path("/");
      assertTrue("/ should be a directory", fs.getFileStatus(path)
          .isDirectory() == true);
      
      byte[] fileData = AppendTestUtil.randomBytes(seed, size*3);
      // create a new file in home directory. Do not close it.
      Path file1 = new Path("filelocal.dat");
      FSDataOutputStream stm = createFile(fs, file1, 1);
  
      // write to file
      stm.write(fileData);
      stm.close();
      
      // now test the skip function
      FSDataInputStream instm = fs.open(file1);
      byte[] actual = new byte[fileData.length];
      // read something from the block first, otherwise BlockReaderLocal.skip()
      // will not be invoked
      int nread = instm.read(actual, 0, 3);
      long skipped = 2*size+3;
      instm.seek(skipped);
      nread = instm.read(actual, (int)(skipped + nread), 3);
      instm.close();
        
    } finally {
      fs.close();
      cluster.shutdown();
    }
  }
     
  /**
   * Test to run benchmarks between short circuit read vs regular read with
   * specified number of threads simultaneously reading.
   * <br>
   * Run this using the following command:
   * bin/hadoop --config confdir \
   * org.apache.hadoop.hdfs.TestShortCircuitLocalRead \
   * <shortcircuit on?> <checsum on?> <Number of threads>
   */
  public static void main(String[] args) throws Exception {    
    if (args.length != 3) {
      System.out.println("Usage: test shortcircuit checksum threadCount");
      System.exit(1);
    }
    boolean shortcircuit = Boolean.valueOf(args[0]);
    boolean checksum = Boolean.valueOf(args[1]);
    int threadCount = Integer.valueOf(args[2]);

    // Setup create a file
    final Configuration conf = new Configuration();
    conf.setBoolean(DFSConfigKeys.DFS_CLIENT_READ_SHORTCIRCUIT_KEY, shortcircuit);
    conf.setBoolean(DFSConfigKeys.DFS_CLIENT_READ_SHORTCIRCUIT_SKIP_CHECKSUM_KEY,
        checksum);
    
    //Override fileSize and DATA_TO_WRITE to much larger values for benchmark test
    int fileSize = 1000 * blockSize + 100; // File with 1000 blocks
    final byte [] dataToWrite = AppendTestUtil.randomBytes(seed, fileSize);
    
    // create a new file in home directory. Do not close it.
    final Path file1 = new Path("filelocal.dat");
    final FileSystem fs = FileSystem.get(conf);
    FSDataOutputStream stm = createFile(fs, file1, 1);
    
    stm.write(dataToWrite);
    stm.close();

    long start = Time.now();
    final int iteration = 20;
    Thread[] threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      threads[i] = new Thread() {
        @Override
        public void run() {
          for (int i = 0; i < iteration; i++) {
            try {
              String user = getCurrentUser();
              checkFileContent(fs.getUri(), file1, dataToWrite, 0, user, conf,
                  true);
            } catch (IOException e) {
              e.printStackTrace();
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
          }
        }
      };
    }
    for (int i = 0; i < threadCount; i++) {
      threads[i].start();
    }
    for (int i = 0; i < threadCount; i++) {
      threads[i].join();
    }
    long end = Time.now();
    System.out.println("Iteration " + iteration + " took " + (end - start));
    fs.delete(file1, false);
  }
}

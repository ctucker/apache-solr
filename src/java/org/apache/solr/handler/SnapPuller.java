/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.lucene.index.IndexCommit;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.FastInputStream;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.NamedListCodec;
import org.apache.solr.core.SolrCore;
import static org.apache.solr.handler.ReplicationHandler.*;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.update.CommitUpdateCommand;
import org.apache.solr.util.RefCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Adler32;
import java.util.zip.Checksum;

/**
 * <p/> Provides functionality equivalent to the snappull script as well as a
 * timer for scheduling pulls from the master.
 * </p>
 *
 * @version $Id$
 * @since solr 1.4
 */
public class SnapPuller {
  private static final Logger LOG = LoggerFactory.getLogger(SnapPuller.class.getName());

  private String masterUrl;

  private ReplicationHandler replicationHandler;

  private Integer pollInterval;

  private String pollIntervalStr;

  private ScheduledExecutorService executorService;

  private long executorStartTime;

  private long replicationStartTime;

  private SolrCore solrCore;

  private List<Map<String, Object>> filesToDownload;

  private List<Map<String, Object>> confFilesToDownload;

  private List<Map<String, Object>> filesDownloaded;

  private List<Map<String, Object>> confFilesDownloaded;

  private Map<String, Object> currentFile;

  private FileFetcher fileFetcher;

  private boolean stop = false;

  /**
   * Disable the timer task for polling
   */
  private AtomicBoolean pollDisabled = new AtomicBoolean(false);

  public SnapPuller(NamedList initArgs, ReplicationHandler handler, SolrCore sc) {
    solrCore = sc;
    masterUrl = (String) initArgs.get(MASTER_URL);
    if (masterUrl == null)
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
              "'masterUrl' is required for a slave");
    this.replicationHandler = handler;
    pollIntervalStr = (String) initArgs.get(POLL_INTERVAL);
    pollInterval = readInterval(pollIntervalStr);
    if (pollInterval != null && pollInterval > 0) {
      startExecutorService();
    } else {
      LOG.info(" No value set for 'pollInterval'. Timer Task not started.");
    }
  }

  private void startExecutorService() {
    Runnable task = new Runnable() {
      public void run() {
        if (pollDisabled.get()) {
          LOG.info("Poll disabled");
          return;
        }
        try {
          executorStartTime = System.currentTimeMillis();
          replicationHandler.doSnapPull();
        } catch (Exception e) {
          LOG.error("Exception in pulling snapshot", e);
        }
      }
    };
    executorService = Executors.newSingleThreadScheduledExecutor();
    executorService.scheduleAtFixedRate(task, pollInterval, pollInterval, TimeUnit.MILLISECONDS);
    LOG.info("Poll Scheduled at an interval of " + pollInterval + "ms");
  }

  /**
   * Gets the latest commit version and generation from the master
   *
   * @param client
   * @return
   * @throws IOException
   */
  @SuppressWarnings("unchecked")
  NamedList getLatestVersion(HttpClient client) throws IOException {
    PostMethod post = new PostMethod(masterUrl);
    post.addParameter(COMMAND, CMD_INDEX_VERSION);
    post.addParameter("wt", "javabin");
    return getNamedListResponse(client, post);
  }

  NamedList getCommandResponse(HttpClient client, String cmd) throws IOException {
    PostMethod post = new PostMethod(masterUrl);
    post.addParameter(COMMAND, cmd);
    post.addParameter("wt", "javabin");
    return getNamedListResponse(client, post);
  }

  private NamedList getNamedListResponse(HttpClient client, PostMethod method)
          throws IOException {
    try {
      int status = client.executeMethod(method);
      if (status != HttpStatus.SC_OK) {
        throw new SolrException(SolrException.ErrorCode.SERVICE_UNAVAILABLE,
                "Request failed for the url " + method);
      }
      return new NamedListCodec().unmarshal(method.getResponseBodyAsStream());
    } finally {
      try {
        method.releaseConnection();
      } catch (Exception e) {
      }
    }
  }

  /**
   * Fetches the list of files in a given snapshot
   *
   * @param version
   * @param client
   * @return
   * @throws IOException
   */
  void fetchFileList(long version, HttpClient client) throws IOException {
    PostMethod post = new PostMethod(masterUrl);
    post.addParameter(COMMAND, CMD_GET_FILE_LIST);
    post.addParameter(CMD_INDEX_VERSION, String.valueOf(version));
    post.addParameter("wt", "javabin");
    NamedList nl = getNamedListResponse(client, post);
    List<Map<String, Object>> f = (List<Map<String, Object>>) nl.get(CMD_GET_FILE_LIST);
    if (f != null)
      filesToDownload = Collections.synchronizedList(f);
    f = (List<Map<String, Object>>) nl.get(CONF_FILES);
    if (f != null)
      confFilesToDownload = Collections.synchronizedList(f);
  }

  /**
   * This command downloads all the necessary files from master to install a
   * snapshot. Only changed files are downloaded.
   *
   * @param core the SolrCore
   * @return true on success, false if slave is already in sync
   * @throws IOException if an exception occurs
   */
  @SuppressWarnings("unchecked")
  boolean fetchLatestIndex(SolrCore core) throws IOException {
    HttpClient client = null;
    replicationStartTime = System.currentTimeMillis();
    try {
      client = new HttpClient();
      client.setHttpConnectionManager(new MultiThreadedHttpConnectionManager());
      NamedList response = getLatestVersion(client);
      long latestVersion = (Long) response.get(CMD_INDEX_VERSION);
      long latestGeneration = (Long) response.get(GENERATION);
      if (latestVersion == 0L) {
        return false;
      }
      IndexCommit commit;
      RefCounted<SolrIndexSearcher> searcherRefCounted = null;
      try {
        searcherRefCounted = core.getSearcher();
        commit = searcherRefCounted.get().getReader().getIndexCommit();
      } finally {
        if (searcherRefCounted != null)
          searcherRefCounted.decref();
      }
      if (commit.getVersion() == latestVersion && commit.getGeneration() == latestGeneration) {
        LOG.info("Slave in sync with master.");
        return false;
      }
      LOG.info("Master's version: " + latestVersion + ", generation: " + latestGeneration);
      LOG.info("Slave's version: " + commit.getVersion() + ", generation: " + commit.getGeneration());
      LOG.info("Starting replication process");
      fetchFileList(latestVersion, client);
      LOG.info("Number of files in latest snapshot in master: " + filesToDownload.size());

      filesDownloaded = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
      boolean isSnapNeeded = commit.getGeneration() >= latestGeneration;
      File tmpIndexDir = createTempindexDir(core);
      if (isIndexStale())
        isSnapNeeded = true;
      boolean successfulInstall = false;
      try {
        File indexDir = new File(core.getIndexDir());
        downloadIndexFiles(isSnapNeeded, tmpIndexDir, client, latestVersion);
        LOG.info("Total time taken for download : " + ((System.currentTimeMillis() - replicationStartTime) / 1000) + " secs");
        Collection<Map<String, Object>> modifiedConfFiles = getModifiedConfFiles(confFilesToDownload);
        if (modifiedConfFiles != null && !modifiedConfFiles.isEmpty()) {
          downloadConfFiles(client, confFilesToDownload, latestVersion);
          if (isSnapNeeded) {
            modifyIndexProps(tmpIndexDir.getName());
          } else {
            successfulInstall = copyIndexFiles(tmpIndexDir, indexDir);
          }
          if (successfulInstall) {
            LOG.info("Configuration files are modified, core will be reloaded");
            logReplicationTimeAndConfFiles(modifiedConfFiles);//write to a file time of replication and conf files.
            reloadCore();
          }
        } else {
          LOG.info("Conf files are not downloaded or are in sync");
          if (isSnapNeeded) {
            modifyIndexProps(tmpIndexDir.getName());
          } else {
            successfulInstall = copyIndexFiles(tmpIndexDir, indexDir);
          }
          if (successfulInstall) {
            logReplicationTimeAndConfFiles(modifiedConfFiles);
            doCommit();
          }
        }
        replicationStartTime = 0;
        return successfulInstall;
      } catch (ReplicationHandlerException e) {
        delTree(tmpIndexDir);
        LOG.error("User aborted Replication");
      } catch (SolrException e) {
        delTree(tmpIndexDir);
        throw e;
      } catch (Exception e) {
        delTree(tmpIndexDir);
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Snappull failed : ", e);
      }
      return successfulInstall;
    } finally {
      filesToDownload = filesDownloaded = confFilesDownloaded = confFilesToDownload = null;
      replicationStartTime = 0;
      fileFetcher = null;
      stop = false;
      client.getHttpConnectionManager().closeIdleConnections(0);
    }
  }

  private void logReplicationTimeAndConfFiles(Collection<Map<String, Object>> modifiedConfFiles) {
    FileOutputStream outFile = null;
    FileInputStream inFile = null;
    List<String> confFiles = new ArrayList<String>();
    if (modifiedConfFiles != null && !modifiedConfFiles.isEmpty())
      for (Map<String, Object> map1 : modifiedConfFiles)
        confFiles.add((String) map1.get(NAME));

    Properties props = new Properties();
    long replicationTime = System.currentTimeMillis();
    try {
      File f = new File(solrCore.getDataDir(), REPLICATION_PROPERTIES);
      int indexCount = 1, confFilesCount = 1;
      if (f.exists()) {
        inFile = new FileInputStream(f);
        props.load(inFile);
        if (!props.isEmpty()) {
          if (props.containsKey("timesIndexReplicated"))
            indexCount = Integer.valueOf(props.getProperty("timesIndexReplicated")) + 1;

          props.setProperty("timesIndexReplicated", String.valueOf(indexCount));
          props.setProperty("indexReplicatedAt", String.valueOf(replicationTime));

          if (modifiedConfFiles != null && !modifiedConfFiles.isEmpty()) {
            props.setProperty("confFilesReplicated", confFiles.toString());
            props.setProperty("confFilesReplicatedAt", String.valueOf(replicationTime));
            if (props.containsKey("timesConfigReplicated"))
              confFilesCount = Integer.valueOf(props.getProperty("timesConfigReplicated")) + 1;
            props.setProperty("timesConfigReplicated", String.valueOf(confFilesCount));
          }
        } else {
          props.setProperty("timesIndexReplicated", String.valueOf(indexCount));
          props.setProperty("indexReplicatedAt", String.valueOf(replicationTime));
          if (modifiedConfFiles != null && !modifiedConfFiles.isEmpty()) {
            props.setProperty("confFilesReplicated", confFiles.toString());
            props.setProperty("confFilesReplicatedAt", String.valueOf(replicationTime));
            props.setProperty("timesConfigReplicated", String.valueOf(confFilesCount));
          }
        }
      } else {
        props.setProperty("timesIndexReplicated", String.valueOf(indexCount));
        props.setProperty("indexReplicatedAt", String.valueOf(replicationTime));
        if (modifiedConfFiles != null && !modifiedConfFiles.isEmpty()) {
          props.setProperty("confFilesReplicated", confFiles.toString());
          props.setProperty("confFilesReplicatedAt", String.valueOf(replicationTime));
          props.setProperty("timesConfigReplicated", String.valueOf(confFilesCount));
        }
      }
      outFile = new FileOutputStream(f);
      props.store(outFile, "Replication details");
    } catch (Exception e) {
      LOG.warn("Exception while updating statistics", e);
    }
    finally {
      closeNoExp(inFile);
      closeNoExp(outFile);
    }
  }

  private void doCommit() throws IOException {
    CommitUpdateCommand cmd = new CommitUpdateCommand(false);
    cmd.waitFlush = true;
    cmd.waitSearcher = true;
    solrCore.getUpdateHandler().commit(cmd);
  }

  private File createTempindexDir(SolrCore core) {
    String snapName = "index."
            + new SimpleDateFormat(SnapShooter.DATE_FMT).format(new Date());
    File snapDir = new File(core.getDataDir(), snapName);
    snapDir.mkdirs();
    return snapDir;
  }

  private void reloadCore() {
    new Thread() {
      public void run() {
        try {
          solrCore.getCoreDescriptor().getCoreContainer().reload(solrCore.getName());
        } catch (Exception e) {
          LOG.error("Could not restart core ", e);
        }
      }
    }.start();
  }

  private void downloadConfFiles(HttpClient client, List<Map<String, Object>> confFilesToDownload, long latestVersion) throws Exception {
    LOG.info("Starting download of configuration files from master: " + confFilesToDownload);
    confFilesDownloaded = Collections.synchronizedList(new ArrayList<Map<String, Object>>());
    File tmpconfDir = new File(solrCore.getResourceLoader().getConfigDir(), "conf." + getDateAsStr(new Date()));
    boolean status = tmpconfDir.mkdirs();
    if (!status) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
              "Failed to create temporary config folder: " + tmpconfDir.getName());
    }
    for (Map<String, Object> file : confFilesToDownload) {
      fileFetcher = new FileFetcher(tmpconfDir, file, (String) file.get(NAME), client, true, latestVersion);
      currentFile = file;
      fileFetcher.fetchFile();
      confFilesDownloaded.add(new HashMap<String, Object>(file));
    }
    copyTmpConfFiles2Conf(tmpconfDir);
  }

  private void downloadIndexFiles(boolean snapNeeded, File snapDir,
                                  HttpClient client, long latestVersion) throws Exception {
    for (Map<String, Object> file : filesToDownload) {
      File localIndexFile = new File(solrCore.getIndexDir(), (String) file.get(NAME));
      if (!localIndexFile.exists() || snapNeeded) {
        fileFetcher = new FileFetcher(snapDir, file, (String) file.get(NAME),
                client, false, latestVersion);
        currentFile = file;
        fileFetcher.fetchFile();
        filesDownloaded.add(new HashMap<String, Object>(file));
      } else {
        LOG.info("Skipping download for " + localIndexFile);
      }
    }
  }

  private boolean isIndexStale() {
    for (Map<String, Object> file : filesToDownload) {
      File localIndexFile = new File(solrCore.getIndexDir(), (String) file
              .get(NAME));
      if (localIndexFile.exists()
              && localIndexFile.length() != (Long) file.get(SIZE)) {
        // file exists and size is different, therefore we must assume
        // corrupted index
        return true;
      }
    }
    return false;
  }

  private boolean copyAFile(File snapDir, File indexDir, String fname, List<String> copiedfiles) {
    File indexFileInSnap = new File(snapDir, fname);
    File indexFileInIndex = new File(indexDir, fname);
    boolean success = indexFileInSnap.renameTo(indexFileInIndex);
    if (!success) {
      LOG.error("Unable to move index file from: " + indexFileInSnap
              + " to: " + indexFileInIndex);
      for (String f : copiedfiles) {
        File indexFile = new File(indexDir, f);
        if (indexFile.exists())
          indexFile.delete();
      }
      delTree(snapDir);
      return false;
    }
    return true;
  }

  private boolean copyIndexFiles(File snapDir, File indexDir) {
    String segmentsFile = null;
    List<String> copiedfiles = new ArrayList<String>();
    for (Map<String, Object> f : filesDownloaded) {
      String fname = (String) f.get(NAME);
      if (fname.startsWith("segments_")) {
        //The segments file must be copied in the end
        //Otherwise , if the copy fails index ends up corrupted
        segmentsFile = fname;
        continue;
      }
      if (!copyAFile(snapDir, indexDir, fname, copiedfiles)) return false;
      copiedfiles.add(fname);
    }
    if (segmentsFile != null) {
      if (!copyAFile(snapDir, indexDir, segmentsFile, copiedfiles)) return false;
    }
    return true;
  }

  private void copyTmpConfFiles2Conf(File tmpconfDir) throws IOException {
    File confDir = new File(solrCore.getResourceLoader().getConfigDir());
    try {
      for (File file : tmpconfDir.listFiles()) {
        File oldFile = new File(confDir, file.getName());
        if (oldFile.exists()) {
          File backupFile = new File(confDir, oldFile.getName() + "." + getDateAsStr(new Date(oldFile.lastModified())));
          boolean status = oldFile.renameTo(backupFile);
          if (!status) {
            throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                    "Unable to rename: " + oldFile + " to: " + backupFile);
          }
        }
        boolean status = file.renameTo(oldFile);
        if (!status) {
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                  "Unable to rename: " + file + " to: " + oldFile);
        }
      }
    } finally {
      delTree(tmpconfDir);
    }
  }

  private String getDateAsStr(Date d) {
    return new SimpleDateFormat(SnapShooter.DATE_FMT).format(d);
  }

  private void modifyIndexProps(String snap) {
    LOG.info("New index installed. Updating index properties...");
    File idxprops = new File(solrCore.getDataDir() + "index.properties");
    Properties p = new Properties();
    if (idxprops.exists()) {
      InputStream is = null;
      try {
        is = new FileInputStream(idxprops);
        p.load(is);
      } catch (Exception e) {
        LOG.error("Unable to load index.properties");
      } finally {
        closeNoExp(is);
      }
    }
    p.put("index", snap);
    FileOutputStream os = null;
    try {
      os = new FileOutputStream(idxprops);
      p.store(os, "index properties");
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
              "Unable to write index.properties", e);
    } finally {
      closeNoExp(os);
    }
  }

  private Collection<Map<String, Object>> getModifiedConfFiles(List<Map<String, Object>> confFilesToDownload) {
    if (confFilesToDownload == null || confFilesToDownload.isEmpty())
      return Collections.EMPTY_LIST;
    Map<String, Map<String, Object>> nameVsFile = new HashMap<String, Map<String, Object>>();
    for (Map<String, Object> map : confFilesToDownload) {
      nameVsFile.put((String) map.get(NAME), map);
    }
    List<Map<String, Object>> localFilesInfo = replicationHandler.getConfFileCache(nameVsFile.keySet());
    for (Map<String, Object> fileInfo : localFilesInfo) {
      String name = (String) fileInfo.get(NAME);
      Map<String, Object> m = nameVsFile.get(name);
      if (m == null) continue;
      if (m.get(CHECKSUM).equals(fileInfo.get(CHECKSUM))) {
        nameVsFile.remove(name);
      }
    }
    return nameVsFile.isEmpty() ? Collections.EMPTY_LIST : nameVsFile.values();
  }

  static boolean delTree(File dir) {
    if (dir == null || !dir.exists())
      return false;
    File contents[] = dir.listFiles();
    if (contents != null) {
      for (File file : contents) {
        if (file.isDirectory()) {
          boolean success = delTree(file);
          if (!success)
            return false;
        } else {
          boolean success = file.delete();
          if (!success)
            return false;
        }
      }
    }
    return dir.delete();
  }

  void disablePoll() {
    pollDisabled.set(true);
    LOG.info("inside disable poll, value of pollDisabled = " + pollDisabled);
  }

  /**
   * Enable polling
   */
  void enablePoll() {
    pollDisabled.set(false);
    LOG.info("inside enable poll, value of pollDisabled = " + pollDisabled);
  }

  /**
   * Stops the ongoing pull
   */
  void abortPull() {
    stop = true;
  }

  long getReplicationStartTime() {
    return replicationStartTime;
  }

  List<Map<String, Object>> getConfFilesToDownload() {
    //make a copy first because it can be null later
    List<Map<String, Object>> tmp = confFilesToDownload;
    return tmp == null ? Collections.EMPTY_LIST : new ArrayList<Map<String, Object>>(tmp);
  }

  List<Map<String, Object>> getConfFilesDownloaded() {
    //make a copy first because it can be null later
    List<Map<String, Object>> tmp = confFilesDownloaded;
    return tmp == null ? Collections.EMPTY_LIST : new ArrayList<Map<String, Object>>(tmp);
  }

  List<Map<String, Object>> getFilesToDownload() {
    //make a copy first because it can be null later
    List<Map<String, Object>> tmp = filesToDownload;
    return tmp == null ? Collections.EMPTY_LIST : new ArrayList<Map<String, Object>>(tmp);
  }

  List<Map<String, Object>> getFilesDownloaded() {
    List<Map<String, Object>> tmp = filesDownloaded;
    return tmp == null ? Collections.EMPTY_LIST : new ArrayList<Map<String, Object>>(tmp);
  }

  Map<String, Object> getCurrentFile() {
    Map<String, Object> tmp = currentFile;
    FileFetcher tmpFileFetcher = fileFetcher;
    if (tmp == null)
      return null;
    tmp = new HashMap<String, Object>(tmp);
    if (tmpFileFetcher != null)
      tmp.put("bytesDownloaded", tmpFileFetcher.bytesDownloaded);
    return tmp;
  }

  boolean isPollingDisabled() {
    return pollDisabled.get();
  }

  Long getNextScheduledExecTime() {
    Long nextTime = null;
    if (executorStartTime > 0)
      nextTime = executorStartTime + pollInterval;
    return nextTime;
  }

  private class ReplicationHandlerException extends InterruptedException {

    public ReplicationHandlerException(String message) {
      super(message);
    }

  }

  private class FileFetcher {
    boolean includeChecksum = true;

    File snapDir;

    String fileName;

    String saveAs;

    long size, lastmodified;

    long bytesDownloaded = 0;

    HttpClient client;

    FileChannel fileChannel;

    byte[] buf = new byte[1024 * 1024];

    Checksum checksum;

    File file;

    int errorCount = 0;

    private boolean isConf;

    private PostMethod post;

    private boolean aborted = false;

    private Long indexVersion;

    FileFetcher(File dir, Map<String, Object> fileDetails, String saveAs,
                HttpClient client, boolean isConf, long latestVersion) throws FileNotFoundException {
      this.snapDir = dir;
      this.fileName = (String) fileDetails.get(NAME);
      this.size = (Long) fileDetails.get(SIZE);
      this.client = client;
      this.isConf = isConf;
      this.saveAs = saveAs;
      indexVersion = latestVersion;

      this.file = new File(snapDir, saveAs);
      this.fileChannel = new FileOutputStream(file).getChannel();
      if (includeChecksum)
        checksum = new Adler32();
    }

    void fetchFile() throws Exception {
      try {
        while (true) {
          final FastInputStream is = getStream();
          int result;
          try {
            result = fetchPackets(is);
            if (result == 0 || result == NO_CONTENT) {
              if (file.exists())
                file.setLastModified(lastmodified);
              return;
            }
          } finally {
            //closing Inputstream and HTTP connection takes a long time,
            // so replication status shows as 'replicating' even though it is aborted.
            new Thread() {
              public void run() {
                closeNoExp(is);
                try {
                  if (post != null)
                    post.releaseConnection();
                } catch (Exception e) {
                }
              }
            }.start();
          }
        }
      } finally {
        cleanup();
      }
    }

    private int fetchPackets(FastInputStream fis) throws Exception {
      byte[] intbytes = new byte[4];
      byte[] longbytes = new byte[8];
      try {
        while (true) {
          if (stop) {
            stop = false;
            aborted = true;
            throw new ReplicationHandlerException("User aborted replication");
          }
          long checkSumServer = -1;
          fis.readFully(intbytes);
          int packetSize = readInt(intbytes);
          if (packetSize <= 0) {
            LOG.warn("No content recieved for file: " + currentFile);
            return NO_CONTENT;
          }
          if (buf.length < packetSize)
            buf = new byte[packetSize];
          if (checksum != null) {
            fis.readFully(longbytes);
            checkSumServer = readLong(longbytes);
          }
          fis.readFully(buf, 0, packetSize);
          if (includeChecksum) {
            checksum.reset();
            checksum.update(buf, 0, packetSize);
            long checkSumClient = checksum.getValue();
            if (checkSumClient != checkSumServer) {
              LOG.error("Checksum not matched between client and server for: " + currentFile);
              return 1;
            }
          }
          fileChannel.write(ByteBuffer.wrap(buf, 0, packetSize));
          bytesDownloaded += packetSize;
          if (bytesDownloaded >= size)
            return 0;
          errorCount = 0;
        }
      } catch (ReplicationHandlerException e) {
        throw e;
      } catch (Exception e) {
        LOG.warn("Error in fetching packets ", e);
        errorCount++;
        if (errorCount > MAX_RETRIES) {
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                  "Snappull failed for file:" + fileName, e);
        }
        return ERR;
      }
    }

    /**
     * The webcontainer flushes the data only after it fills the buffer size.
     * So, all data has to be read as readFully() other wise it fails. So read
     * everything as bytes and then extract int out of it
     *
     * @param b
     * @return
     */
    private int readInt(byte[] b) {
      return (((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16)
              | ((b[2] & 0xff) << 8) | (b[3] & 0xff));

    }

    /**
     * Same as above but to read long
     *
     * @param b
     * @return
     */
    private long readLong(byte[] b) {
      return (((long) (b[0] & 0xff)) << 56) | (((long) (b[1] & 0xff)) << 48)
              | (((long) (b[2] & 0xff)) << 40) | (((long) (b[3] & 0xff)) << 32)
              | (((long) (b[4] & 0xff)) << 24) | ((b[5] & 0xff) << 16)
              | ((b[6] & 0xff) << 8) | ((b[7] & 0xff));

    }

    private void cleanup() {
      try {
        fileChannel.close();
      } catch (Exception e) {/* noop */
      }
      if (bytesDownloaded != size) {
        try {
          file.delete();
        } catch (Exception e) {
          LOG.error("Error deleting file in cleanup" + e.getMessage());
        }
        if (!aborted)
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                  "Unable to download " + fileName + " completely. Downloaded "
                          + bytesDownloaded + "!=" + size);
      }
    }

    FastInputStream getStream() throws IOException {
      post = new PostMethod(masterUrl);
      post.addParameter(COMMAND, CMD_GET_FILE);
      post.addParameter(CMD_INDEX_VERSION, indexVersion.toString());
      if (isConf) {
        post.addParameter(CONF_FILE_SHORT, fileName);
      } else {
        post.addParameter(FILE, fileName);
      }
      if (this.includeChecksum)
        post.addParameter(CHECKSUM, "true");
      post.addParameter("wt", FILE_STREAM);
      if (bytesDownloaded > 0) {
        post.addParameter(OFFSET, "" + bytesDownloaded);
      }
      client.executeMethod(post);
      InputStream is = post.getResponseBodyAsStream();
      return new FastInputStream(is);
    }
  }


  static Integer readInterval(String interval) {
    Pattern pattern = Pattern.compile(INTERVAL_PATTERN);
    if (interval == null)
      return null;
    int result = 0;
    if (interval != null) {
      Matcher m = pattern.matcher(interval.trim());
      if (m.find()) {
        String hr = m.group(1);
        String min = m.group(2);
        String sec = m.group(3);
        result = 0;
        try {
          if (sec != null && sec.length() > 0)
            result += Integer.parseInt(sec);
          if (min != null && min.length() > 0)
            result += (60 * Integer.parseInt(min));
          if (hr != null && hr.length() > 0)
            result += (60 * 60 * Integer.parseInt(hr));
          result *= 1000;
        } catch (NumberFormatException e) {
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                  INTERVAL_ERR_MSG);
        }
      } else {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
                INTERVAL_ERR_MSG);
      }

    }
    return result;
  }

  public void destroy() {
    if (executorService != null) executorService.shutdown();
  }

  String getMasterUrl() {
    return masterUrl;
  }

  String getPollInterval() {
    return pollIntervalStr;
  }

  private static final int MAX_RETRIES = 5;

  private static final int NO_CONTENT = 1;

  private static final int ERR = 2;

  public static final String REPLICATION_PROPERTIES = "replication.properties";

  public static final String POLL_INTERVAL = "pollInterval";

  public static final String INTERVAL_ERR_MSG = "The " + POLL_INTERVAL + " must be in this format 'HH:mm:ss'";

  private static final String INTERVAL_PATTERN = "(\\d*?):(\\d*?):(\\d*)";
}

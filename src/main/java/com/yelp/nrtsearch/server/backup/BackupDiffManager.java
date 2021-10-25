/*
 * Copyright 2021 Yelp Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yelp.nrtsearch.server.backup;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.UUID.randomUUID;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.*;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackupDiffManager implements Archiver {
  private static final int NUM_S3_THREADS = 20;
  private final ThreadPoolExecutor executor =
      (ThreadPoolExecutor) Executors.newFixedThreadPool(NUM_S3_THREADS);

  private final ContentDownloader contentDownloader;
  private final FileCompressAndUploader fileCompressAndUploader;
  private final VersionManager versionManager;
  private final Path archiverDirectory;
  private static final Logger logger = LoggerFactory.getLogger(BackupDiffManager.class);

  public static class BackupDiffInfo {
    private final Set<String> alreadyUploaded;
    private final Set<String> toBeAdded;
    private final Set<String> toBeRemoved;

    public BackupDiffInfo(
        ImmutableSet<String> alreadyUploaded,
        ImmutableSet<String> toBeAdded,
        ImmutableSet<String> toBeRemoved) {
      this.alreadyUploaded = alreadyUploaded;
      this.toBeAdded = toBeAdded;
      this.toBeRemoved = toBeRemoved;
    }

    public static BackupDiffInfo generateBackupDiffInfo(
        Set<String> oldFileNames, Set<String> currentFileNames) {
      return new BackupDiffInfo(
          Sets.intersection(oldFileNames, currentFileNames).immutableCopy(),
          Sets.difference(currentFileNames, oldFileNames).immutableCopy(),
          Sets.difference(oldFileNames, currentFileNames).immutableCopy());
    }

    public Set<String> getToBeAdded() {
      return toBeAdded;
    }

    public Set<String> getAlreadyUploaded() {
      return alreadyUploaded;
    }

    public Set<String> getToBeRemoved() {
      return toBeRemoved;
    }
  }

  public static class TempDirManager implements AutoCloseable {
    private static final String TMP_SUFFIX = ".tmp";
    private final Path tmpDir;

    public TempDirManager(Path baseDir) {
      this.tmpDir = baseDir.resolve(getTmpName());
    }

    private String getTmpName() {
      return randomUUID() + TMP_SUFFIX;
    }

    private void deleteTempDir() throws IOException {
      if (Files.exists(tmpDir)) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(tmpDir)) {
          for (Path file : ds) {
            Files.delete(file);
          }
          Files.delete(tmpDir);
        }
      }
    }

    public Path getPath() {
      return tmpDir;
    }

    @Override
    public void close() throws Exception {
      deleteTempDir();
    }
  }

  public static class BackupDiffDirValidator {
    public static Path validateMetaFile(Path downloadedDir) throws IOException {
      List<Path> files = new ArrayList<>();
      if (!Files.exists(downloadedDir)) {
        throw new RuntimeException(
            String.format("File Info Directory %s does not exist locally", downloadedDir));
      }
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(downloadedDir)) {
        for (Path file : ds) {
          if (Files.isDirectory(file)) {
            throw new RuntimeException(
                String.format(
                    "File Info Directory %s cannot contain subdirs: %s", downloadedDir, file));
          }
          files.add(file.getFileName());
        }
        if (files.size() != 1) {
          throw new RuntimeException(
              String.format(
                  "File Info Directory %s: cannot contain multiple files %s",
                  downloadedDir, files.size()));
        }
        return Paths.get(downloadedDir.toString(), files.get(0).toString());
      }
    }
  }

  public static class BackupDiffMarshaller {
    public static List<String> deserializeFileNames(Path backupDiffFile) throws IOException {
      String indexFileName;
      List<String> indexFileNames = new LinkedList<>();
      try (BufferedReader br =
          new BufferedReader(new InputStreamReader(new FileInputStream(backupDiffFile.toFile())))) {
        while ((indexFileName = br.readLine()) != null) {
          indexFileNames.add(indexFileName);
        }
      }
      return indexFileNames;
    }

    public static void serializeFileNames(List<String> indexFileNames, Path destBackupDiffFile)
        throws IOException {
      try (BufferedWriter bw =
          new BufferedWriter(
              new OutputStreamWriter(new FileOutputStream(destBackupDiffFile.toFile())))) {
        for (String indexFileName : indexFileNames) {
          bw.write(indexFileName);
          bw.newLine();
        }
      }
    }
  }

  public BackupDiffManager(
      final ContentDownloader contentDownloader,
      final FileCompressAndUploader fileCompressAndUploader,
      final VersionManager versionManager,
      final Path archiverDirectory) {
    this.contentDownloader = contentDownloader;
    this.fileCompressAndUploader = fileCompressAndUploader;
    this.versionManager = versionManager;
    this.archiverDirectory = archiverDirectory;
  }

  public BackupDiffInfo generateDiff(
      String serviceName, String indexName, Collection<String> currentIndexFileNames)
      throws IOException {
    // get the latest backup file names
    List<String> backupIndexFileNames = getLatestBackupIdxFileNames(serviceName, indexName);
    BackupDiffInfo backupInfo =
        BackupDiffInfo.generateBackupDiffInfo(
            new HashSet<>(backupIndexFileNames), new HashSet<>(currentIndexFileNames));
    return backupInfo;
  }

  public String uploadDiff(String serviceName, String resourceName, BackupDiffInfo backupDiffInfo) {
    try (TempDirManager tmpDir = new TempDirManager(archiverDirectory)) {
      Path tmpPath = tmpDir.getPath();
      List<String> fileNames = new ArrayList<>(backupDiffInfo.getAlreadyUploaded());
      fileNames.addAll(backupDiffInfo.getToBeAdded());
      Path diffFile = getTempDiffFile(tmpPath);
      BackupDiffMarshaller.serializeFileNames(fileNames, diffFile);
      logger.info(
          "Uploading diff file: index {}, service: {}, diffFile: {} ",
          resourceName,
          serviceName,
          diffFile);
      String diffFileName = diffFile.getFileName().toString();
      fileCompressAndUploader.upload(serviceName, resourceName, diffFileName, tmpPath);
      return diffFileName;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Path getTempDiffFile(Path tmpPath) throws IOException {
    if (!Files.exists(tmpPath)) {
      Files.createDirectory(tmpPath);
    }
    return Paths.get(tmpPath.toString(), randomUUID().toString());
  }

  @Override
  public Path download(String serviceName, String resource) throws IOException {
    // get the latest backup file names
    List<String> backupIndexFileNames = getLatestBackupIdxFileNames(serviceName, resource);
    LinkedList<Future<Boolean>> futures = new LinkedList<>();
    List<Path> downloadDirs = new LinkedList<>();
    for (String indexFileName : backupIndexFileNames) {
      futures.add(
          executor.submit(
              () -> {
                try {
                  Path downloadDir = getTmpDir();
                  downloadDirs.add(Paths.get(downloadDir.toString(), indexFileName));
                  return contentDownloader.getVersionContent(
                      serviceName, resource, indexFileName, downloadDir);
                } catch (IOException e) {
                  throw new RuntimeException();
                }
              }));
    }
    while (!futures.isEmpty()) {
      try {
        futures.pollFirst().get();
      } catch (Exception e) {
        throw new RuntimeException("Error downloading index File", e);
      }
    }
    Path downloadDir = collectDownloadedFiles(downloadDirs);
    return downloadDir;
  }

  private Path collectDownloadedFiles(List<Path> downloadDirs) throws IOException {
    Path downloadDir = Files.createDirectory(getTmpDir());
    for (Path source : downloadDirs) {
      Files.move(source, downloadDir.resolve(source.getFileName()), REPLACE_EXISTING);
      Files.delete(source.getParent());
    }
    return downloadDir;
  }

  private Path getTmpDir() {
    return Paths.get(archiverDirectory.toString(), randomUUID().toString());
  }

  @Override
  public String upload(
      String serviceName,
      String resource,
      Path path, // path to resource_name/shard0/index/
      Collection<String> filesToInclude,
      Collection<String> parentDirectoriesToInclude,
      boolean stream)
      throws IOException {
    BackupDiffManager.BackupDiffInfo backupDiffInfo =
        generateDiff(serviceName, resource, filesToInclude);

    // upload new files since last backup
    uploadFiles(serviceName, resource, path, backupDiffInfo.getToBeAdded());

    // uploadDiff file itself
    return uploadDiff(serviceName, resource, backupDiffInfo);
  }

  public void uploadFiles(
      String serviceName, String resourceName, Path indexFilePath, Collection<String> files) {
    final LinkedList<Future> futures = new LinkedList<>();
    for (String currFileName : files) {
      futures.add(
          executor.submit(
              () -> {
                try {
                  fileCompressAndUploader.upload(
                      serviceName, resourceName, currFileName, indexFilePath);
                } catch (IOException e) {
                  // TODO: need to catch this upstream and handle appropriately
                  throw new RuntimeException(e);
                }
              }));
    }
    while (!futures.isEmpty()) {
      try {
        futures.pollFirst().get();
      } catch (Exception e) {
        throw new RuntimeException("Error downloading file part", e);
      }
    }
  }

  public boolean blessVersion(String serviceName, String resourceName, String diffFile) {
    return versionManager.blessVersion(serviceName, resourceName, diffFile);
  }

  @Override
  public boolean deleteVersion(String serviceName, String resource, String versionHash)
      throws IOException {
    return false;
  }

  @Override
  public List<String> getResources(String serviceName) {
    return null;
  }

  @Override
  public List<VersionedResource> getVersionedResource(String serviceName, String resource) {
    return null;
  }

  private List<String> getLatestBackupIdxFileNames(String serviceName, String indexName)
      throws IOException {
    if (versionManager.getLatestVersionNumber(serviceName, indexName) < 0) {
      logger.warn(
          String.format(
              "No prior backups found for service: %s, resource: %s, will proceed with full backup",
              serviceName, indexName));
      return Collections.emptyList();
    }
    final String latestVersion =
        versionManager.getVersionString(serviceName, indexName, "_latest_version");
    final String versionHash =
        versionManager.getVersionString(serviceName, indexName, latestVersion);
    try (TempDirManager tmpDir = new TempDirManager(archiverDirectory)) {
      Path tmpPath = tmpDir.getPath();
      logger.info(
          "Downloading latest file info: index {}, service: {}, version: {} to directory {}",
          indexName,
          serviceName,
          versionHash,
          tmpPath);
      boolean result =
          contentDownloader.getVersionContent(serviceName, indexName, versionHash, tmpPath);
      if (!result) {
        // TODO: should this be an Exception?
        logger.warn(
            "Downloading latest file info: index {}, service: {}, version: {} to directory {} failed since dir already exists",
            indexName,
            serviceName,
            versionHash,
            tmpPath);
      }
      // confirm there is only 1 file within this directory
      Path metaFile = BackupDiffDirValidator.validateMetaFile(tmpPath);
      return BackupDiffMarshaller.deserializeFileNames(metaFile);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
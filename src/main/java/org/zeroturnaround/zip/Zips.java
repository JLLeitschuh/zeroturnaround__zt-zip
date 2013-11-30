/**
 *    Copyright (C) 2012 ZeroTurnaround LLC <support@zeroturnaround.com>
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.zeroturnaround.zip;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.zeroturnaround.zip.transform.ZipEntryTransformer;
import org.zeroturnaround.zip.transform.ZipEntryTransformerEntry;

/**
 * Fluent api for zip handling.
 *
 * @author shelajev
 *
 */
public class Zips {

  /**
   * Source archive. TODO: make it work on streams
   */
  private final File src;

  /**
   * Optional destination archive, if null, src will be overwrittene
   */
  private File dest;

  /**
   * Charset to use for entry names
   */
  private Charset charset;

  /**
   * Flag to carry timestamps of entries on.
   */
  private boolean preserveTimestamps;

  /**
   * List<ZipEntrySource>
   */
  private List changedEntries = new ArrayList();

  /**
   * List<String>
   */
  private Set removedEntries = new HashSet();

  /**
   * List<ZipEntryTransformerEntry>
   */
  private List transformers = new ArrayList();

  /*
   * If you want many name mappers here, you can create some compound instance that knows if
   * it wants to stop after first successfull mapping or go through all transformations, if null means
   * stop and ignore entry or that name mapper didn't know how to transform, etc.
   */
  private NameMapper nameMapper;

  /**
   * Flag to show that we want the final result to be unpacked
   */
  private boolean unpackedResult;

  private Zips(File src) {
    this.src = src;
  }

  /**
   * Static factory method to obtain an instance of Zips.
   *
   * @param src zip file to process
   * @return instance of Zips
   */
  public static Zips get(File src) {
    return new Zips(src);
  }

  /**
   * Static factory method to obtain an instance of Zips without source file.
   * See {@link #get(File src)}.
   *
   * @return instance of Zips
   */
  public static Zips create() {
    return new Zips(null);
  }

  /**
   * Specifies an entry to add or change to the output when this Zips executes.
   * Adding takes precedence over removal of entries.
   *
   * @param entry entry to add
   * @return this Zips for fluent api
   */
  public Zips addEntry(ZipEntrySource entry) {
    this.changedEntries.add(entry);
    return this;
  }

  /**
   * Specifies entries to add or change to the output when this Zips executes.
   * Adding takes precedence over removal of entries.
   *
   * @param entries entries to add
   * @return this Zips for fluent api
   */
  public Zips addEntries(ZipEntrySource[] entries) {
    this.changedEntries.addAll(Arrays.asList(entries));
    return this;
  }

  /**
   * Adds a file entry. If given file is a dir, adds it and all subfiles recursively.
   * Adding takes precedence over removal of entries.
   *
   * @param file file to add.
   * @return this Zips for fluent api
   */
  public Zips addFile(File file) {
    return addFile(file, false, null);
  }

  /**
   * Adds a file entry. If given file is a dir, adds it and all subfiles recursively.
   * Adding takes precedence over removal of entries.
   *
   * @param file file to add.
   * @param preserveRoot if file is a directory, true indicates we want to preserve this dir in the zip.
   *          otherwise children of the file are added directly under root.
   * @return this Zips for fluent api
   */
  public Zips addFile(File file, boolean preserveRoot) {
    return addFile(file, preserveRoot, null);
  }

  /**
   * Adds a file entry. If given file is a dir, adds it and all subfiles recursively.
   * Adding takes precedence over removal of entries.
   *
   * @param file file to add.
   * @param filter a filter to accept files for adding, null means all files are accepted
   * @return this Zips for fluent api
   */
  public Zips addFile(File file, FileFilter filter) {
    return this.addFile(file, false, filter);
  }

  /**
   * Adds a file entry. If given file is a dir, adds it and all subfiles recursively.
   * Adding takes precedence over removal of entries.
   *
   * @param file file to add.
   * @param preserveRoot if file is a directory, true indicates we want to preserve this dir in the zip.
   *          otherwise children of the file are added directly under root.
   * @param filter a filter to accept files for adding, null means all files are accepted
   * @return this Zips for fluent api
   */
  public Zips addFile(File file, boolean preserveRoot, FileFilter filter) {
    if (!file.isDirectory()) {
      this.changedEntries.add(new FileSource(file.getName(), file));
      return this;
    }
    Collection files = FileUtils.listFiles(file, null, true);
    for (Iterator iter = files.iterator(); iter.hasNext();) {
      File entryFile = (File) iter.next();
      if (filter != null && !filter.accept(entryFile)) {
        continue;
      }
      String entryPath = getRelativePath(file, entryFile);
      if (preserveRoot) {
        entryPath = file.getName() + entryPath;
      }
      if (entryPath.startsWith("/")) {
        entryPath = entryPath.substring(1);
      }
      this.changedEntries.add(new FileSource(entryPath, entryFile));
    }
    return this;
  }

  private String getRelativePath(File parent, File file) {
    String parentPath = parent.getPath();
    String filePath = file.getPath();
    if (!filePath.startsWith(parentPath)) {
      throw new IllegalArgumentException("File " + file + " is not a child of " + parent);
    }
    return filePath.substring(parentPath.length());
  }

  /**
   * Specifies an entry to remove to the output when this Zips executes.
   *
   * @param entry path of the entry to remove
   * @return this Zips for fluent api
   */
  public Zips removeEntry(String entry) {
    this.removedEntries.add(entry);
    return this;
  }

  /**
   * Specifies entries to remove to the output when this Zips executes.
   *
   * @param entries paths of the entry to remove
   * @return this Zips for fluent api
   */
  public Zips removeEntries(String[] entries) {
    this.removedEntries.addAll(Arrays.asList(entries));
    return this;
  }

  /**
   * Enables timestamp preserving for this Zips execution
   *
   * @return this Zips for fluent api
   */
  public Zips preserveTimestamps() {
    this.preserveTimestamps = true;
    return this;
  }

  /**
   * Specifies timestamp preserving for this Zips execution
   *
   * @param preserve flag to preserve timestamps
   * @return this Zips for fluent api
   */
  public Zips setPreserveTimestamps(boolean preserve) {
    this.preserveTimestamps = true;
    return this;
  }

  /**
   * Specifies charset for this Zips execution
   *
   * @param charset charset to use
   * @return this Zips for fluent api
   */
  public Zips charset(Charset charset) {
    this.charset = charset;
    return this;
  }

  /**
   * Specifies destination file for this Zips execution,
   * if destination is null (default value), then source file will be overwritten.
   * Temporary file will be used as destination and then written over the source to
   * create an illusion if inplace action.
   *
   * @param destination charset to use
   * @return this Zips for fluent api
   */
  public Zips destination(File destination) {
    this.dest = destination;
    return this;
  }

  /**
   *
   * @param nameMapper to use when processing entries
   * @return this Zips for fluent api
   */
  public Zips nameMapper(NameMapper nameMapper) {
    this.nameMapper = nameMapper;
    return this;
  }

  public Zips unpack() {
    this.unpackedResult = true;
    return this;
  }

  /**
   * @return true if destination is not specified.
   */
  private boolean isInPlace() {
    return dest == null;
  }

  /**
   * @return should the result of the processing be unpacked.
   */
  private boolean isUnpack() {
    return unpackedResult || (dest != null && dest.isDirectory());
  }

  /**
   * Registers a transformer for a given entry.
   *
   * @param path entry to transform
   * @param transformer transformer for the entry
   * @return this Zips for fluent api
   */
  public Zips addTransformer(String path, ZipEntryTransformer transformer) {
    this.transformers.add(new ZipEntryTransformerEntry(path, transformer));
    return this;
  }

  /**
   * Iterates through source Zip entries removing or changing them according to
   * set parameters.
   */
  public void process() {
    if (src == null && dest == null) {
      throw new IllegalArgumentException("Source and destination shouldn't be null together");
    }

    ZipEntryTransformerEntry[] transformersArray = getTransformersArray();

    File destinationFile = null;
    try {
      destinationFile = getDestinationFile();
      ZipOutputStream out = null;
      ZipEntryOrInfoAdapter zipEntryAdapter = null;

      if (destinationFile.isFile()) {
        out = ZipFileUtil.createZipOutputStream(new BufferedOutputStream(new FileOutputStream(destinationFile)), charset);
        zipEntryAdapter = new ZipEntryOrInfoAdapter(new CopyingCallback(transformersArray, out, preserveTimestamps), null);
      }
      else { // directory
        zipEntryAdapter = new ZipEntryOrInfoAdapter(new UnpackingCallback(transformersArray, destinationFile), null);
      }
      try {
        processAllEntries(zipEntryAdapter);
        handleInPlaceActions(destinationFile);
      }
      finally {
        IOUtils.closeQuietly(out);
      }
    }
    catch (IOException e) {
      ZipExceptionUtil.rethrow(e);
    }
    finally {
      if (isInPlace()) {
        // destinationZip is a temporary file
        FileUtils.deleteQuietly(destinationFile);
      }
    }
  }

  private void processAllEntries(ZipEntryOrInfoAdapter zipEntryAdapter) {
    iterateChangedAndAdded(zipEntryAdapter);
    iterateExistingExceptRemoved(zipEntryAdapter);
  }

  private File getDestinationFile() throws IOException {
    if(isUnpack()) {
      if(isInPlace()) {
        File tempFile = File.createTempFile("zips", null);
        FileUtils.deleteQuietly(tempFile);
        tempFile.mkdirs(); // temp dir created
        return tempFile;
      }
      else {
        if (!dest.isDirectory()) {
          // destination is a directory, actually we shouldn't be here, because this should mean we want an unpacked result.
          FileUtils.deleteQuietly(dest);
          File result = new File(dest.getAbsolutePath());
          result.mkdirs(); // create a directory instead of dest file
          return result;
        }
        return dest;
      }
    }
    else {
      // we need a file
      if(isInPlace()) { // no destination specified, temp file
        return File.createTempFile("zips", ".zip");
      }
      else {
        if(dest.isDirectory()) {
          // destination is a directory, actually we shouldn't be here, because this should mean we want an unpacked result.
          FileUtils.deleteQuietly(dest);
          return new File(dest.getAbsolutePath());
        }
        return dest;
      }
    }
  }

  /**
   * Reads the source ZIP file and executes the given callback for each entry.
   * <p>
   * For each entry the corresponding input stream is also passed to the callback. If you want to stop the loop then throw a ZipBreakException.
   *
   * This method is charset aware and uses Zips.charset.
   *
   * @param zipEntryCallback
   *          callback to be called for each entry.
   *
   * @see ZipEntryCallback
   *
   */
  public void iterate(ZipEntryCallback zipEntryCallback) {
    ZipEntryOrInfoAdapter zipEntryAdapter = new ZipEntryOrInfoAdapter(zipEntryCallback, null);
    processAllEntries(zipEntryAdapter);
  }

  /**
   * Scans the source ZIP file and executes the given callback for each entry.
   * <p>
   * Only the meta-data without the actual data is read. If you want to stop the loop then throw a ZipBreakException.
   *
   * This method is charset aware and uses Zips.charset.
   *
   * @param callback
   *          callback to be called for each entry.
   *
   * @see ZipInfoCallback
   * @see #iterate(ZipEntryCallback)
   */
  public void iterate(ZipInfoCallback callback) {
    ZipEntryOrInfoAdapter zipEntryAdapter = new ZipEntryOrInfoAdapter(null, callback);

    processAllEntries(zipEntryAdapter);
  }

  // ///////////// private api ///////////////

  /**
   * Iterate through source for not removed entries with a given callback
   *
   * @param zipEntryCallback callback to execute on entries or their info.
   */
  private void iterateExistingExceptRemoved(ZipEntryOrInfoAdapter zipEntryCallback) {
    if (src == null) {
      // if we don't have source specified, then we have nothing to iterate.
      return;
    }
    final Set removedDirs = ZipUtil.filterDirEntries(src, removedEntries);

    ZipFile zf = null;
    try {
      zf = getZipFile();

      // manage existing entries
      Enumeration en = zf.entries();
      while (en.hasMoreElements()) {
        ZipEntry entry = (ZipEntry) en.nextElement();
        String entryName = entry.getName();
        if (removedEntries.contains(entryName) || isEntryInDir(removedDirs, entryName)) {
          // removed entries are
          continue;
        }

        if (nameMapper != null) {
          String mappedName = nameMapper.map(entry.getName());
          if (mappedName == null) {
            continue; // we should ignore this entry
          }
          else if (!mappedName.equals(entry.getName())) {
            // if name is different, do nothing
            entry = ZipEntryUtil.copy(entry, mappedName);
          }
        }

        InputStream is = zf.getInputStream(entry);
        try {
          zipEntryCallback.process(is, entry);
        }
        catch (ZipBreakException ex) {
          break;
        }
        finally {
          IOUtils.closeQuietly(is);
        }
      }
    }
    catch (IOException e) {
      ZipExceptionUtil.rethrow(e);
    }
    finally {
      ZipUtil.closeQuietly(zf);
    }
  }

  /**
   * Iterate through ZipEntrySources for added or changed entries with a given callback
   *
   * @param zipEntryCallback callback to execute on entries or their info
   */
  private void iterateChangedAndAdded(ZipEntryOrInfoAdapter zipEntryCallback) {
    for (Iterator it = changedEntries.iterator(); it.hasNext();) {
      ZipEntrySource entrySource = (ZipEntrySource) it.next();
      try {
        ZipEntry entry = entrySource.getEntry();
        if (nameMapper != null) {
          String mappedName = nameMapper.map(entry.getName());
          if (mappedName == null) {
            continue; // we should ignore this entry
          }
          else if (!mappedName.equals(entry.getName())) {
            // if name is different, do nothing
            entry = ZipEntryUtil.copy(entry, mappedName);
          }
        }
        zipEntryCallback.process(entrySource.getInputStream(), entry);
      }
      catch (ZipBreakException ex) {
        break;
      }
      catch (IOException e) {
        ZipExceptionUtil.rethrow(e);
      }
    }
  }

  /**
   * if we are doing something in place, move result file into src.
   *
   * @param result destination zip file
   */
  private void handleInPlaceActions(File result) throws IOException {
    if (isInPlace()) {
      // we operate in-place
      FileUtils.forceDelete(src);
      if (result.isFile()) {
        FileUtils.moveFile(result, src);
      }
      else {
        FileUtils.moveDirectory(result, src);
      }
    }
  }

  /**
   * Checks if entry given by name resides inside of one of the dirs.
   *
   * @param dirNames dirs
   * @param entryName entryPath
   */
  private boolean isEntryInDir(Set dirNames, String entryName) {
    // this should be done with a trie, put dirNames in a trie and check if entryName leads to
    // some node or not.
    Iterator iter = dirNames.iterator();
    while (iter.hasNext()) {
      String dirName = (String) iter.next();
      if (entryName.startsWith(dirName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @return transformers as array. Replace with .toArray, when we accept generics
   */
  private ZipEntryTransformerEntry[] getTransformersArray() {
    ZipEntryTransformerEntry[] result = new ZipEntryTransformerEntry[transformers.size()];
    int idx = 0;
    Iterator iter = transformers.iterator();
    while (iter.hasNext()) {
      result[idx++] = (ZipEntryTransformerEntry) iter.next();
    }
    return result;
  }

  /**
   * Creates a ZipFile from src and charset of this object. If a constructor with charset is
   * not available, throws an exception.
   *
   * @return ZipFile
   * @throws IOException if ZipFile cannot be constructed
   * @throws IllegalArgumentException if accessing constructor ZipFile(File, Charset)
   *
   */
  private ZipFile getZipFile() throws IOException {
    return ZipFileUtil.getZipFile(src, charset);
  }

  private static class CopyingCallback implements ZipEntryCallback {

    private final Map entryByPath;
    private final ZipOutputStream out;
    private final Set visitedNames;
    private final boolean preserveTimestapms;

    private CopyingCallback(ZipEntryTransformerEntry[] entries, ZipOutputStream out, boolean preserveTimestapms) {
      this.out = out;
      this.preserveTimestapms = preserveTimestapms;
      entryByPath = ZipUtil.byPath(entries);
      visitedNames = new HashSet();
    }

    public void process(InputStream in, ZipEntry zipEntry) throws IOException {
      String entryName = zipEntry.getName();

      if (visitedNames.contains(entryName)) {
        return;
      }
      visitedNames.add(entryName);

      ZipEntryTransformer transformer = (ZipEntryTransformer) entryByPath.remove(entryName);
      if (transformer == null) { // no transformer
        ZipEntryUtil.copyEntry(zipEntry, in, out, preserveTimestapms);
      }
      else { // still transfom entry
        transformer.transform(in, zipEntry, out);
      }
    }
  }

  private static class UnpackingCallback implements ZipEntryCallback {

    private final Map entryByPath;
    private final Set visitedNames;
    private final File destination;

    private UnpackingCallback(ZipEntryTransformerEntry[] entries, File destination) {
      this.destination = destination;
      this.entryByPath = ZipUtil.byPath(entries);
      visitedNames = new HashSet();
    }

    public void process(InputStream in, ZipEntry zipEntry) throws IOException {
      String entryName = zipEntry.getName();

      if (visitedNames.contains(entryName)) {
        return;
      }
      visitedNames.add(entryName);

      File file = new File(destination, entryName);
      if (zipEntry.isDirectory()) {
        FileUtils.forceMkdir(file);
        return;
      }
      else {
        FileUtils.forceMkdir(file.getParentFile());
        file.createNewFile();
      }

      ZipEntryTransformer transformer = (ZipEntryTransformer) entryByPath.remove(entryName);
      if (transformer == null) { // no transformer
        FileUtil.copy(in, file);
      }
      else { // still transform entry
        transformIntoFile(transformer, in, zipEntry, file);
      }
    }

    private void transformIntoFile(final ZipEntryTransformer transformer, final InputStream entryIn, final ZipEntry zipEntry, final File destination) throws IOException {
      final PipedInputStream pipedIn = new PipedInputStream();
      final PipedOutputStream pipedOut = new PipedOutputStream(pipedIn);

      final ZipOutputStream zipOut = new ZipOutputStream(pipedOut);
      final ZipInputStream zipIn = new ZipInputStream(pipedIn);

      ExecutorService newFixedThreadPool = Executors.newFixedThreadPool(1);

      try {
        newFixedThreadPool.execute(new Runnable() {
          public void run() {
            try {
              transformer.transform(entryIn, zipEntry, zipOut);
            }
            catch (IOException e) {
              ZipExceptionUtil.rethrow(e);
            }
          }
        });
        zipIn.getNextEntry();
        FileUtil.copy(zipIn, destination);
      }
      finally {
        try {
          zipIn.closeEntry();
        }
        catch (IOException e) {
          // closing quietly
        }

        newFixedThreadPool.shutdown();
        IOUtils.closeQuietly(pipedIn);
        IOUtils.closeQuietly(zipIn);
        IOUtils.closeQuietly(pipedOut);
        IOUtils.closeQuietly(zipOut);
      }

    }
  }
}

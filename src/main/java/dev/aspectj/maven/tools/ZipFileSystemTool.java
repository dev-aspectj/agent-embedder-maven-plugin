package dev.aspectj.maven.tools;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;
import java.nio.file.*;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipOutputStream;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class ZipFileSystemTool {
  public final static int JAVA_VERSION_MAJOR = Integer.parseInt(System.getProperty("java.version").split("[.]")[0]);

  private static final Map<String, String> ZIP_FS_CREATE_MODE = Collections.singletonMap("create", "true");
  private static final MethodHandle newFileSystem_JRE13;

  static {
    try {
      // Java 13+ has a new constructor capable of creating a zip FS from a path in create-if-not-exists mode
      if (JAVA_VERSION_MAJOR >= 13) {
        final MethodHandles.Lookup lookup = MethodHandles.lookup();
        final MethodType signature = MethodType.methodType(FileSystem.class, Path.class, Map.class);
        //noinspection JavaLangInvokeHandleSignature: Before JDK 13, the method does not exist.
        newFileSystem_JRE13 = lookup.findStatic(FileSystems.class, "newFileSystem", signature);
      } else
        newFileSystem_JRE13 = null;
    }
    catch (NoSuchMethodException | IllegalAccessException unexpectedException) {
      throw new RuntimeException(
        "Cannot find method handle. " +
          "This should never happen, the method should exist on JDK 13+.",
        unexpectedException
      );
    }
  }

  public static FileSystem getZipFS(Path jarPath, boolean create) throws IOException {
    if (!create && !Files.exists(jarPath))
      return null;

    final Path jarPathParent = jarPath.getParent();
    if (jarPathParent != null)
      Files.createDirectories(jarPathParent);

    // Java 13+ has a new constructor capable of creating a zip FS from a path in create-if-not-exists mode
    if (JAVA_VERSION_MAJOR >= 13) {
      try {
        return (FileSystem) newFileSystem_JRE13.invoke(jarPath, ZIP_FS_CREATE_MODE);
      }
      catch (WrongMethodTypeException | ClassCastException thrownByMethodHandlerInvocation) {
        // Re-throw exception that can happen in MethodHandle.invoke
        throw thrownByMethodHandlerInvocation;
      }
      catch (IOException | RuntimeException thrownByInvokedMethod) {
        // Re-throw exception that can happen in called method
        throw thrownByInvokedMethod;
      }
      catch (Throwable unexpected) {
        throw new RuntimeException(unexpected);
      }
    }

    // Java 12 can open a zip FS from a path, if the zip archive exists already.
    // Therefore, create an empty zip archive first, if necessary.
    if (JAVA_VERSION_MAJOR == 12) {
      if (!Files.exists(jarPath))
        try (ZipOutputStream emptyZip = new ZipOutputStream(Files.newOutputStream(jarPath))) {}
      //noinspection RedundantCast
      return FileSystems.newFileSystem(jarPath, (ClassLoader) null);
    }

    // On Java <= 11, trying to create a nested zip FS yields a ProviderNotFoundException. Therefore, we need a
    // temporary top-level copy of the zip archive. On Java <= 11, a zip FS also must be located on the default FS.
    // I.e., we also cannot put the temp-file on an in-memory FS. If we wanted to do that, we would have to unpack it.
    return new VirtualNestedZipFileSystem(jarPath);
  }

  public static class VirtualNestedZipFileSystem extends FileSystem {
    private Path zipPath;
    private Path tempDir;
    private Path tempFile;
    private FileSystem delegate;

    public VirtualNestedZipFileSystem(Path zipPath) throws IOException {
      this.zipPath = zipPath;
      tempDir = Files.createTempDirectory(null);
      tempFile = tempDir.resolve("temp.zip");
      if (Files.exists(zipPath))
        Files.copy(zipPath, tempFile);
      else
        try (ZipOutputStream emptyZip = new ZipOutputStream(Files.newOutputStream(tempFile))) {}
      //noinspection RedundantCast
      delegate = FileSystems.newFileSystem(tempFile, (ClassLoader) null);
    }

    @Override
    public FileSystemProvider provider() {
      return delegate.provider();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
      Files.move(tempFile, zipPath, REPLACE_EXISTING);
      Files.delete(tempDir);
    }

    @Override
    public boolean isOpen() {
      return delegate.isOpen();
    }

    @Override
    public boolean isReadOnly() {
      return delegate.isReadOnly();
    }

    @Override
    public String getSeparator() {
      return delegate.getSeparator();
    }

    @Override
    public Iterable<Path> getRootDirectories() {
      return delegate.getRootDirectories();
    }

    @Override
    public Iterable<FileStore> getFileStores() {
      return delegate.getFileStores();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
      return delegate.supportedFileAttributeViews();
    }

    @Override
    public Path getPath(String first, String... more) {
      return delegate.getPath(first, more);
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
      return delegate.getPathMatcher(syntaxAndPattern);
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
      return delegate.getUserPrincipalLookupService();
    }

    @Override
    public WatchService newWatchService() throws IOException {
      return delegate.newWatchService();
    }
  }
}

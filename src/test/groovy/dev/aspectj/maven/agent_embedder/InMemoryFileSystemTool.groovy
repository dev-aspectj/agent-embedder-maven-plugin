package dev.aspectj.maven.agent_embedder

import com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder
import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import groovy.transform.builder.Builder
import groovy.transform.builder.SimpleStrategy

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

import static dev.aspectj.maven.agent_embedder.AgentEmbedderMojo.MANIFEST_PATH
import static dev.aspectj.maven.tools.ZipFileSystemTool.getZipFS
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING

@Builder(
  builderStrategy = SimpleStrategy,
  prefix = '',
  includes = [
    'agentJarLayoutDescriptor1', 'agentJarManifest1', 'agentJarLocation1', 'doCreateAgentJar1',
    'agentJarLayoutDescriptor2', 'agentJarManifest2', 'agentJarLocation2', 'doCreateAgentJar2',
    'targetJarLayoutDescriptor', 'targetJarManifest', 'targetJarLocation',
    'nestedAgentJarLocation1', 'doCreateNestedAgentJar1',
    'nestedAgentJarLocation2', 'doCreateNestedAgentJar2',
    'debug'
  ]
)
class InMemoryFileSystemTool implements AutoCloseable {

  // The unit tests should run successfully with both JimFS and MemoryFileSystem.
  // Decide here, which in-memory FS to use.
  static USE_JIMFS = false

  // We need a file system configuration with the working dir set to '/'.
  // See https://github.com/google/jimfs/issues/74
  static final Configuration JIMFS_CONFIG = Configuration.unix().toBuilder().setWorkingDirectory('/').build()

  // These defaults can be overridden by builder methods
  String agentJarLayoutDescriptor1 = 'src/test/resources/files_aspectjweaver-jar.txt'
  String agentJarLayoutDescriptor2 = 'src/test/resources/files_my-agent-jar.txt'
  String agentJarManifest1 = 'src/test/resources/manifest_aspectjweaver-jar.txt'
  String agentJarManifest2 = 'src/test/resources/manifest_my-agent-jar.txt'
  String agentJarLocation1 = '/home/me/.m2/repository/org/aspectj/aspectjweaver/1.9.21/aspectjweaver-1.9.21.jar'
  String agentJarLocation2 = '/home/me/.m2/repository/org/acme/my-agent/3.5/my-agent-3.5.jar'
  String targetJarLayoutDescriptor = 'src/test/resources/files_spring-boot-jar.txt'
  String targetJarManifest = 'src/test/resources/manifest_spring-boot-jar.txt'
  String targetJarLocation = '/home/me/projects/my-project/target/my-project-1.0.jar'
  String nestedAgentJarLocation1 = '/BOOT-INF/lib/aspectjweaver-1.9.21.jar'
  String nestedAgentJarLocation2 = '/BOOT-INF/lib/my-agent-3.5.jar'
  boolean doCreateAgentJar1 = true
  boolean doCreateAgentJar2 = true
  boolean doCreateNestedAgentJar1 = true
  boolean doCreateNestedAgentJar2 = true
  boolean debug = false

  // These are not to exposed to builder methods
  private FileSystem hostFS

  FileSystem createHostFS() {
    hostFS = createEmptyFS()
    if (doCreateAgentJar1)
      createAgentJar1(hostFS)
    if (doCreateAgentJar2)
      createAgentJar2(hostFS)
    createTargetJar(hostFS)
    if (doCreateNestedAgentJar1) {
      try (FileSystem targetJarFS = getTargetJarFS(false)) {
        createNestedAgentJar1(targetJarFS)
      }
    }
    if (doCreateNestedAgentJar2) {
      try (FileSystem targetJarFS = getTargetJarFS(false)) {
        createNestedAgentJar2(targetJarFS)
      }
    }
    if (debug)
      dumpFS(hostFS, 'host')
    hostFS
  }

  FileSystem createEmptyFS() {
    return USE_JIMFS ? Jimfs.newFileSystem(JIMFS_CONFIG) : MemoryFileSystemBuilder.newEmpty().build()
  }

  void createAgentJar1(FileSystem fileSystem) {
    createJar(fileSystem, agentJarLocation1, agentJarLayoutDescriptor1, agentJarManifest1, 'agent JAR')
  }

  void createAgentJar2(FileSystem fileSystem) {
    createJar(fileSystem, agentJarLocation2, agentJarLayoutDescriptor2, agentJarManifest2, 'agent JAR 1')
  }

  void createTargetJar(FileSystem fileSystem) {
    createJar(fileSystem, targetJarLocation, targetJarLayoutDescriptor, targetJarManifest, 'target JAR 2')
  }

  void createNestedAgentJar1(FileSystem fileSystem) {
    createJar(fileSystem, nestedAgentJarLocation1, agentJarLayoutDescriptor1, targetJarManifest, 'nested agent JAR 1')
  }

  void createNestedAgentJar2(FileSystem fileSystem) {
    createJar(fileSystem, nestedAgentJarLocation2, agentJarLayoutDescriptor2, targetJarManifest, 'nested agent JAR 2')
  }

  void createJar(FileSystem fileSystem, String location, String layoutDescriptor, String manifest, String debugLabel) {
    try (FileSystem jarFS = getZipFS(fileSystem.getPath(location), true)) {
      populateFS(jarFS, layoutDescriptor)
      if (manifest) {
        // In populateFS, a default one-line manifest is created, if MANIFEST_PATH is found in the JAR layout
        // descriptor. Therefore, explicitly work in REPLACE_EXISTING mode.
        // TODO: Remove '[] as String[]' after https://issues.apache.org/jira/browse/GROOVY-11293 fix
        Files.copy(FileSystems.default.getPath(manifest, [] as String[]), jarFS.getPath(MANIFEST_PATH, [] as String[]), REPLACE_EXISTING)
      }
      if (debug)
        dumpFS(jarFS, debugLabel)
    }
  }

  FileSystem getAgentJarFS(boolean create) {
    getZipFS(hostFS.getPath(agentJarLocation1), create)
  }

  FileSystem getTargetJarFS(boolean create) {
    getZipFS(hostFS.getPath(targetJarLocation), create)
  }

  FileSystem getNestedAgentJarFS(boolean create) {
    // Do not use try-with-resources or otherwise close targetJarFS. Otherwise, using the nested FS will yield a
    // ClosedFileSystemException. I.e., we have a FS leak here. But as soon as the root FS is closed, all subordinate
    // ones will be closed, too.
    FileSystem targetJarFS = getTargetJarFS(false)
    getZipFS(targetJarFS.getPath(nestedAgentJarLocation1), create)
  }

  static void populateFS(FileSystem fileSystem, String layoutDescriptor) {
    final Path manifestPath = fileSystem.getPath(MANIFEST_PATH)
    // TODO: Remove '[] as String[]' after https://issues.apache.org/jira/browse/GROOVY-11293 fix
    Files.lines(FileSystems.default.getPath(layoutDescriptor, [] as String[])).forEach { line ->
      if (line.startsWith('#'))
        return
      Path path = fileSystem.getPath(line)
      if (line.endsWith('/'))
        Files.createDirectories(path)
      else {
        if (manifestPath == path) {
          // Lines added to a manifest are only written, if the manifest contains a version header
          Files.write(path, ('Manifest-Version: 1.0' + System.lineSeparator()).bytes)
        } else
          Files.createFile(path)
      }
    }
  }

  void dumpFS(FileSystem fileSystem, String label) {
    println getFSDump(fileSystem, label)
  }

  String getHostFSDump() {
    getFSDump(hostFS, 'host')
  }

  String getAgentFSDump() {
    try (def agentJarFS = getAgentJarFS(false)) {
      getFSDump(agentJarFS, 'agent JAR')
    }
  }

  String getTargetFSDump() {
    try (def targetJarFS = getTargetJarFS(false)) {
      getFSDump(targetJarFS, 'target JAR')
    }
  }

  static String getFSDump(FileSystem fileSystem, String label) {
    new StringBuilder().tap {
      append(String.format('File system dump for %s%n', label))
      fileSystem.rootDirectories.each { rootDir ->
        Files.find(rootDir, Integer.MAX_VALUE, (path, attributes) -> true)
          .each { path -> append(String.format('%,10d %s%n', Files.size(path), path)) }
      }
    }
  }

  List<FileInfo> getHostFSInfo() {
    getFSInfo(hostFS)
  }

  List<FileInfo> getAgentFSInfo() {
    try (def agentJarFS = getAgentJarFS(false)) {
      getFSInfo(agentJarFS)
    }
  }

  List<FileInfo> getTargetFSInfo() {
    try (def targetJarFS = getTargetJarFS(false)) {
      getFSInfo(targetJarFS)
    }
  }

  static List<FileInfo> getFSInfo(FileSystem fileSystem) {
    new ArrayList<FileInfo>().tap {
      fileSystem?.rootDirectories?.each { rootDir ->
        Files.find(rootDir, Integer.MAX_VALUE, (path, attributes) -> true)
          .each { path -> add(new FileInfo(path, Files.size(path), Files.isDirectory(path))) }
      }
    }
  }

  @Override
  void close() throws Exception {
    hostFS.close()
  }

  static void main(String[] args) {
    InMemoryFileSystemTool fileSystemCreator = new InMemoryFileSystemTool().debug(false)

    try (FileSystem hostFS = fileSystemCreator.createHostFS()) {
      fileSystemCreator.dumpFS(hostFS, 'host')
      try (FileSystem agentJarFS = fileSystemCreator.getAgentJarFS(false)) {
        fileSystemCreator.dumpFS(agentJarFS, 'agent')
      }
      try (FileSystem targetJarFS = fileSystemCreator.getTargetJarFS(false)) {
        fileSystemCreator.dumpFS(targetJarFS, 'target')
      }
      try (FileSystem nestedAgentJarFS = fileSystemCreator.getNestedAgentJarFS(false)) {
        fileSystemCreator.dumpFS(nestedAgentJarFS, 'nested agent')
      }
    }
  }

}

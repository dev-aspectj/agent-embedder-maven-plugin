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

@Builder(
  builderStrategy = SimpleStrategy,
  prefix = '',
  includes = [
    'agentJarLayoutDescriptor', 'agentJarManifest', 'agentJarLocation', 'doCreateAgentJar',
    'targetJarLayoutDescriptor', 'targetJarManifest', 'targetJarLocation',
    'nestedAgentJarLocation', 'doCreateNestedAgentJar',
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
  String agentJarLayoutDescriptor = 'src/test/resources/files_aspectjweaver-jar.txt'
  String agentJarManifest = 'src/test/resources/manifest_aspectjweaver-jar.txt'
  String agentJarLocation = '/home/me/.m2/repository/org/aspectj/aspectjweaver/1.9.21/aspectjweaver-1.9.21.jar'
  String targetJarLayoutDescriptor = 'src/test/resources/files_spring-boot-jar.txt'
  String targetJarManifest = 'src/test/resources/manifest_spring-boot-jar.txt'
  String targetJarLocation = '/home/me/projects/my-project/target/my-project-1.0.jar'
  String nestedAgentJarLocation = '/BOOT-INF/lib/aspectjweaver-1.9.21.jar'
  boolean doCreateAgentJar = true
  boolean doCreateNestedAgentJar = true
  boolean debug = false

  // These are not to exposed to builder methods
  private FileSystem hostFS

  FileSystem createHostFS() {
    hostFS = USE_JIMFS ? Jimfs.newFileSystem(JIMFS_CONFIG) : MemoryFileSystemBuilder.newEmpty().build()
    if (doCreateAgentJar)
      createAgentJar(hostFS)
    createTargetJar(hostFS)
    if (doCreateNestedAgentJar) {
      try (FileSystem targetJarFS = getTargetJarFS(false)) {
        createNestedAgentJar(targetJarFS)
      }
    }
    if (debug)
      dumpFS(hostFS, 'host')
    hostFS
  }

  void createAgentJar(FileSystem fileSystem) {
    createJar(fileSystem, agentJarLocation, agentJarLayoutDescriptor, 'agent JAR')
  }

  void createTargetJar(FileSystem fileSystem) {
    createJar(fileSystem, targetJarLocation, targetJarLayoutDescriptor, 'target JAR')
  }

  void createNestedAgentJar(FileSystem fileSystem) {
    createJar(fileSystem, nestedAgentJarLocation, agentJarLayoutDescriptor, 'nested agent JAR')
  }

  void createJar(FileSystem fileSystem, String location, String layoutDescriptor, String debugLabel) {
    try (FileSystem jarFS = getJarFS(fileSystem, location, true)) {
      populateFS(jarFS, new File(layoutDescriptor))
      if (debug)
        dumpFS(jarFS, debugLabel)
    }
  }

  FileSystem getAgentJarFS(boolean create) {
    getJarFS(hostFS, agentJarLocation, create)
  }

  FileSystem getTargetJarFS(boolean create) {
    getJarFS(hostFS, targetJarLocation, create)
  }

  FileSystem getNestedAgentJarFS(boolean create) {
    // Do not use try-with-resources or otherwise close targetJarFS. Otherwise, using the nested FS will yield a
    // ClosedFileSystemException. I.e., we have a FS leak here. But as soon as the root FS is closed, all subordinate
    // ones will be closed, too.
    FileSystem targetJarFS = getTargetJarFS(false)
    getJarFS(targetJarFS, nestedAgentJarLocation, create)
  }

  static FileSystem getJarFS(FileSystem hostFS, String location, boolean create) {
    Path jarPath = hostFS.getPath(location)
    if (!create && !Files.exists(jarPath))
      return null
    Files.createDirectories(jarPath.parent)
    FileSystems.newFileSystem(jarPath, [create: 'true'], (ClassLoader) null)
  }

  static void populateFS(FileSystem fileSystem, File resourceFile) {
    final Path manifestPath = fileSystem.getPath(MANIFEST_PATH)
    resourceFile.eachLine("UTF-8") { line, lineNo ->
      if (line.startsWith('#'))
        return null
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

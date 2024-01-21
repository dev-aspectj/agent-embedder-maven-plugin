package dev.aspectj.maven.agent_embedder

import com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder
import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll
import spock.util.environment.Jvm

import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipOutputStream

/**
 * Creates nested ZIP/JAR files in three scenarios:
 * <ul>
 *   <li>on disk</li>
 *   <li>in memory on JimFS</li>
 *   <li>in memory on MemoryFileSystem</li>
 * </ul>
 * This is not a test for the application code as such but rather a showcase for how to handle nested JARs elegantly
 * with Java NIO. It is also a good smoke test for the two in-memory FS libraries used in other tests.
 */
@Issue('https://github.com/marschall/memoryfilesystem/issues/156')
class NestedZipTest extends Specification {
  // TODO: Remove '[] as String[]' workaround from 'getPath()' everywhere, after
  //       https://issues.apache.org/jira/browse/GROOVY-11293 has been fixed.
  @Unroll('#scenario')
  def 'create nested zip file'() {
    given: 'a text file on the source FS'
    def sourceFS = FileSystems.default
    def rootTxtPath = sourceFS.getPath('root.txt', [] as String[])
    Files.write(rootTxtPath, 'Hello root!'.bytes)

    when: 'creating a zip FS on the target FS, creating text file and copying one from the source FS'
    def outerZipPath = targetFS.getPath('outer.zip', [] as String[])
    if (Files.exists(outerZipPath))
      Files.delete(outerZipPath)
    def outerZipFS = createZipFS(outerZipPath)
    Files.write(outerZipFS.getPath('outer.txt'), 'Hello outer!'.bytes)
    Files.copy(rootTxtPath, outerZipFS.getPath('from-root.txt', [] as String[]))

    and: 'creating a zip FS inside the outer zip file, creating text file and copying one from the source FS'
    def innerZipPath = outerZipFS.getPath('inner.zip')
    def innerZipFS = createZipFS(innerZipPath)
    Files.write(innerZipFS.getPath('inner.txt', [] as String[]), 'Hello inner!'.bytes)
    Files.copy(rootTxtPath, innerZipFS.getPath('from-root.txt', [] as String[]))

    and: 'creating a zip FS inside the inner zip file, creating text file and copying one from the source FS'
    def inner2ZipPath = innerZipFS.getPath('inner2.zip', [] as String[])
    def inner2ZipFS = createZipFS(inner2ZipPath)
    Files.write(inner2ZipFS.getPath('inner2.txt', [] as String[]), 'Hello inner2!'.bytes)
    Files.copy(rootTxtPath, inner2ZipFS.getPath('from-root.txt', [] as String[]))

    then: 'no errors occur'
    noExceptionThrown()

    cleanup:
    inner2ZipFS?.close()
    innerZipFS?.close()
    outerZipFS?.close()
    if (outerZipPath && Files.exists(outerZipPath))
      Files.delete(outerZipPath)
    if (rootTxtPath && Files.exists(rootTxtPath))
      Files.delete(rootTxtPath)

    where:
    scenario           | targetFS
    'on disk'          | FileSystems.default
    'JimFS'            | Jimfs.newFileSystem(Configuration.unix().toBuilder().setWorkingDirectory('/').build())
    'MemoryFileSystem' | MemoryFileSystemBuilder.newEmpty().build()
  }

  FileSystem createZipFS(Path zipPath) {
    // Java 13+ has a new constructor capable of creating a zip FS from a path in 'create' mode
    if (Jvm.current.java13Compatible)
      return FileSystems.newFileSystem(zipPath, [create: 'true'])

    // Older Java versions can only open a zip FS from a path, if the zip archive exists already.
    // Therefore, create an empty zip archive first.
    new ZipOutputStream(Files.newOutputStream(zipPath)).close()
    return FileSystems.newFileSystem(zipPath, null as ClassLoader)
  }
}

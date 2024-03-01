package dev.aspectj.maven.agent_embedder

import com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder
import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

import java.nio.file.FileSystems
import java.nio.file.Files

import static dev.aspectj.maven.tools.ZipFileSystemTool.getZipFS

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
  @Unroll('#scenario')
  def 'create nested zip file'() {
    given: 'a text file on the source FS'
    def sourceFS = FileSystems.default
    def rootTxtPath = sourceFS.getPath('root.txt')
    Files.write(rootTxtPath, 'Hello root!'.bytes)

    when: 'creating a zip FS on the target FS, creating text file and copying one from the source FS'
    def outerZipPath = targetFS.getPath('outer.zip')
    if (Files.exists(outerZipPath))
      Files.delete(outerZipPath)
    def outerZipFS = getZipFS(outerZipPath, true)
    Files.write(outerZipFS.getPath('outer.txt'), 'Hello outer!'.bytes)
    Files.copy(rootTxtPath, outerZipFS.getPath('from-root.txt'))

    and: 'creating a zip FS inside the outer zip file, creating text file and copying one from the source FS'
    def innerZipPath = outerZipFS.getPath('inner.zip')
    def innerZipFS = getZipFS(innerZipPath, true)
    Files.write(innerZipFS.getPath('inner.txt'), 'Hello inner!'.bytes)
    Files.copy(rootTxtPath, innerZipFS.getPath('from-root.txt'))

    and: 'creating a zip FS inside the inner zip file, creating text file and copying one from the source FS'
    def inner2ZipPath = innerZipFS.getPath('inner2.zip')
    def inner2ZipFS = getZipFS(inner2ZipPath, true)
    Files.write(inner2ZipFS.getPath('inner2.txt'), 'Hello inner2!'.bytes)
    Files.copy(rootTxtPath, inner2ZipFS.getPath('from-root.txt'))

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
    if (targetFS != FileSystems.default)
      targetFS?.close()

    where:
    scenario           | targetFS
    'on disk'          | FileSystems.default
    'JimFS'            | Jimfs.newFileSystem(Configuration.unix().toBuilder().setWorkingDirectory('/').build())
    'MemoryFileSystem' | MemoryFileSystemBuilder.newEmpty().build()
  }
}

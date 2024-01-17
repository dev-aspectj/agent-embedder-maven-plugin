package dev.aspectj.maven.agent_embedder

import com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder
import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import spock.lang.Issue
import spock.lang.Specification
import spock.lang.Unroll

import java.nio.file.FileSystems
import java.nio.file.Files

@Issue('https://github.com/marschall/memoryfilesystem/issues/156')
class NestedZipTest extends Specification {
  @Unroll('#scenario')
  def 'create nested zip file'() {
    given: 'a text file on the default FS'
    def sourceFS = FileSystems.default
    def rootTxtPath = sourceFS.getPath('root.txt')
    Files.write(rootTxtPath, 'Hello root!'.bytes)

    when: 'creating a zip FS on the target FS, adding two text files'
    def outerZipPath = targetFS.getPath('outer.zip')
    if (Files.exists(outerZipPath))
      Files.delete(outerZipPath)
    def outerZipFS = FileSystems.newFileSystem(outerZipPath, [create: 'true'])
    Files.write(outerZipFS.getPath('outer.txt'), 'Hello outer!'.bytes)
    Files.copy(rootTxtPath, outerZipFS.getPath('from-root.txt'))

    and: 'creating a zip FS inside the outer zip file, adding two text files'
    def innerZipPath = outerZipFS.getPath('inner.zip')
    def innerZipFS = FileSystems.newFileSystem(innerZipPath, [create: 'true'])
    Files.write(innerZipFS.getPath('inner.txt'), 'Hello inner!'.bytes)
    Files.copy(rootTxtPath, innerZipFS.getPath('from-root.txt'))

    and: 'creating a zip FS inside the inner zip file, adding two text files'
    def inner2ZipPath = innerZipFS.getPath('inner2.zip')
    def inner2ZipFS = FileSystems.newFileSystem(inner2ZipPath, [create: 'true'])
    Files.write(inner2ZipFS.getPath('inner2.txt'), 'Hello inner2!'.bytes)
    Files.copy(rootTxtPath, inner2ZipFS.getPath('from-root.txt'))

    then: 'no errors occur'
    noExceptionThrown()

    cleanup:
    inner2ZipFS?.close()
    innerZipFS?.close()
    outerZipFS?.close()

    where:
    scenario           | targetFS
    'on disk'          | FileSystems.default
    'JimFS'            | Jimfs.newFileSystem(Configuration.unix().toBuilder().setWorkingDirectory('/').build())
    'MemoryFileSystem' | MemoryFileSystemBuilder.newEmpty().build()
  }
}

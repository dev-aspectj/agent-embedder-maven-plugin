package dev.aspectj.maven.agent_embedder


import org.apache.maven.artifact.DefaultArtifact
import org.apache.maven.artifact.handler.ArtifactHandler
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import spock.lang.Specification

import java.nio.file.FileSystem
import java.nio.file.Files

class AgentEmbedderMojoTest extends Specification {
  Log log = Mock()

  def 'call embedder steps manually'() {
    given:
    InMemoryFileSystemTool fsTool = new InMemoryFileSystemTool()
      .doCreateAgentJar(doCreateAgentJar)
      .doCreateNestedAgentJar(doCreateNestedAgentJar)
    //.debug(true)
    FileSystem hostFS = fsTool.createHostFS()
    List<FileInfo> hostFSInfo = fsTool.hostFSInfo
    List<FileInfo> agentFSInfo = fsTool.agentFSInfo
    List<FileInfo> targetFSInfo = fsTool.targetFSInfo
    AgentEmbedderMojo mojo = new AgentEmbedderMojo(hostFS: hostFS, removeEmbeddedAgents: true, log: log)

    expect:
    !doCreateAgentJar || hostFSInfo.find { it.path.toString() == fsTool.agentJarLocation }

    when:
    mojo.javaAgents = [new JavaAgent('org.aspectj', 'aspectjweaver', null, 'org.aspectj.weaver.loadtime.Agent', null)]
    List<String> manifestLines
    try (FileSystem targetJarFS = fsTool.getTargetJarFS(false)) {
      mojo.addLauncherAgentToManifest(targetJarFS, 'org.aspectj.weaver.loadtime.Agent')
      manifestLines = Files.lines(targetJarFS.getPath('META-INF/MANIFEST.MF')).toList()
    }
    // Refresh meta data after FS operation
    targetFSInfo = fsTool.targetFSInfo

    then:
    manifestLines.find { it == 'Launcher-Agent-Class: org.aspectj.weaver.loadtime.Agent' }
    1 * log.debug('Setting manifest attribute \'Launcher-Agent-Class: org.aspectj.weaver.loadtime.Agent\'')

    and:
    !targetFSInfo.find { it.path.toString() == '/org/aspectj/weaver/loadtime/Agent.class' }
    !doCreateNestedAgentJar || targetFSInfo.find { it.path.toString() == '/BOOT-INF/lib/aspectjweaver-1.9.21.jar' }

    when:
    try (FileSystem targetJarFS = fsTool.getTargetJarFS(false)) {
      mojo.unpackAgentJar(targetJarFS, fsTool.agentJarLocation)
    }
    // Refresh meta data after FS operation
    targetFSInfo = fsTool.targetFSInfo

    then:
    targetFSInfo.find { it.path.toString() == '/org/aspectj/weaver/loadtime/Agent.class' }
    !targetFSInfo.find { it.path.toString() == '/BOOT-INF/lib/aspectjweaver-1.9.21.jar' }

    and:
    (900.._) * log.debug({ String msg -> msg.startsWith('Unpacking: ') })
    (doCreateNestedAgentJar ? 1 : 0) * log.info('Removing embedded java agent: /BOOT-INF/lib/aspectjweaver-1.9.21.jar')

    cleanup:
    hostFS?.close()

    where:
    doCreateAgentJar | doCreateNestedAgentJar
    true             | true
    true             | false
    false            | true
//    false            | false
  }

  def 'execute embedder mojo'() {
    given:
    InMemoryFileSystemTool fsTool = new InMemoryFileSystemTool()
      .doCreateAgentJar(doCreateAgentJar)
      .doCreateNestedAgentJar(doCreateNestedAgentJar)
    //.debug(true)
    FileSystem hostFS = fsTool.createHostFS()
    List<FileInfo> hostFSInfo = fsTool.hostFSInfo
    List<FileInfo> agentFSInfo = fsTool.agentFSInfo
    List<FileInfo> targetFSInfo = fsTool.targetFSInfo
    AgentEmbedderMojo mojo = new AgentEmbedderMojo(hostFS: hostFS, removeEmbeddedAgents: true, log: log)

    expect:
    !doCreateAgentJar || hostFSInfo.find { it.path.toString() == fsTool.agentJarLocation }
    !doCreateNestedAgentJar || targetFSInfo.find { it.path.toString() == '/BOOT-INF/lib/aspectjweaver-1.9.21.jar' }
    !targetFSInfo.find { it.path.toString() == '/org/aspectj/weaver/loadtime/Agent.class' }

    when:
    mojo.javaAgents = [new JavaAgent('org.aspectj', 'aspectjweaver', null, 'org.aspectj.weaver.loadtime.Agent', null)]
    DefaultArtifact javaAgentArtifact = Spy(new DefaultArtifact('org.aspectj', 'aspectjweaver', '1.9.21', 'compile', 'jar', null, Mock(ArtifactHandler))) {
      // Mock Maven API, i.e. we cannot use Java NIO
      getFile() >> new File(fsTool.agentJarLocation)
    }
    DefaultArtifact buildArtifact = Spy(new DefaultArtifact('dev.aspectj', 'my-project', '1.0', 'compile', 'jar', null, Mock(ArtifactHandler))) {
      // Mock Maven API, i.e. we cannot use Java NIO
      getFile() >> new File(fsTool.targetJarLocation)
    }
    mojo.project = Mock(MavenProject) {
      getArtifacts() >> [javaAgentArtifact]
      getArtifact() >> buildArtifact
    }
    mojo.execute()

    // Refresh meta data after FS operation
    targetFSInfo = fsTool.targetFSInfo

    List<String> manifestLines
    try (FileSystem targetJarFS = fsTool.getTargetJarFS(false)) {
      manifestLines = Files.lines(targetJarFS.getPath('META-INF/MANIFEST.MF')).toList()
    }

    then:
    manifestLines.find { it == 'Launcher-Agent-Class: org.aspectj.weaver.loadtime.Agent' }
    1 * log.debug('Setting manifest attribute \'Launcher-Agent-Class: org.aspectj.weaver.loadtime.Agent\'')

    and:
    targetFSInfo.find { it.path.toString() == '/org/aspectj/weaver/loadtime/Agent.class' }
    !targetFSInfo.find { it.path.toString() == '/BOOT-INF/lib/aspectjweaver-1.9.21.jar' }

    and:
    (900.._) * log.debug({ String msg -> msg.startsWith('Unpacking: ') })
    (doCreateNestedAgentJar ? 1 : 0) * log.info('Removing embedded java agent: /BOOT-INF/lib/aspectjweaver-1.9.21.jar')

    cleanup:
    hostFS?.close()

    where:
    doCreateAgentJar | doCreateNestedAgentJar
    true             | true
    true             | false
    false            | true
//    false            | false
  }

  def 'path separator is adjusted correctly'() {
    given:
    FileSystem fileSystem = Mock() {
      getSeparator() >> fsSeparator
    }

    when:
    def adjustedPath = new AgentEmbedderMojo().adjustPathSeparatorToHostFS(inputPath, fileSystem)

    then:
    adjustedPath.contains(fsSeparator)
    !adjustedPath.contains(File.separator) || File.separator == fsSeparator

    where:
    fsSeparator | inputPath
    '\\'        | 'c:\\Users\\me\\Documents\\foo.txt'
    '/'         | 'c:\\Users\\me\\Documents\\foo.txt'
    '\\'        | '../other/directory/hello.html'
    '/'         | '../other/directory/hello.html'
  }
}

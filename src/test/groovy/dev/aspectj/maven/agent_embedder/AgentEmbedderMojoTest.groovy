package dev.aspectj.maven.agent_embedder

import org.apache.maven.artifact.DefaultArtifact
import org.apache.maven.artifact.handler.ArtifactHandler
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import spock.lang.Specification
import spock.lang.Unroll

import java.nio.file.FileSystem
import java.nio.file.Files
import java.util.jar.Manifest

import static dev.aspectj.maven.agent_embedder.JavaAgentLauncher.AGENT_ATTRIBUTES_GROUP

class AgentEmbedderMojoTest extends Specification {
  Log log = Mock()

  @Unroll('#scenario')
  def 'call embedder steps manually'() {
    given:
    InMemoryFileSystemTool fsTool = new InMemoryFileSystemTool()
      .doCreateAgentJar1(doCreateAgentJar)
      .doCreateAgentJar2(doCreateAgentJar)
      .doCreateNestedAgentJar1(doCreateNestedAgentJar)
      .doCreateNestedAgentJar2(doCreateNestedAgentJar)
    //.debug(true)
    FileSystem hostFS = fsTool.createHostFS()
    List<FileInfo> hostFSInfo = fsTool.hostFSInfo
    List<FileInfo> agentFSInfo = fsTool.agentFSInfo
    List<FileInfo> targetFSInfo = fsTool.targetFSInfo
    AgentEmbedderMojo mojo = new AgentEmbedderMojo(hostFS: hostFS, removeEmbeddedAgents: true, log: log)

    expect:
    !doCreateAgentJar || hostFSInfo.find { it.path.toString() == fsTool.agentJarLocation1 }
    !doCreateAgentJar || hostFSInfo.find { it.path.toString() == fsTool.agentJarLocation2 }
    !doCreateNestedAgentJar || targetFSInfo.find { it.path.toString() == '/BOOT-INF/lib/aspectjweaver-1.9.21.jar' }
    !doCreateNestedAgentJar || targetFSInfo.find { it.path.toString() == '/BOOT-INF/lib/my-agent-3.5.jar' }
    !targetFSInfo.find { it.path.toString() == '/org/aspectj/weaver/loadtime/Agent.class' }
    !targetFSInfo.find { it.path.toString() == '/org/acme/MyAgent.class' }

    when:
    mojo.javaAgents = [
      new JavaAgentInfo('org.aspectj', 'aspectjweaver', null, 'org.aspectj.weaver.loadtime.Agent', null, null),
      new JavaAgentInfo('org.acme', 'my-agent', null, 'org.acme.MyAgent', 'arg1=one,arg2=two', null)
    ]
    Manifest updatedManifest = new Manifest()
    try (FileSystem targetJarFS = fsTool.getTargetJarFS(false)) {
      new AgentEmbedderMojo.ManifestUpdater(mojo, targetJarFS).update()
      mojo.embedLauncherAgent(targetJarFS)
      updatedManifest.read(Files.newInputStream(targetJarFS.getPath('META-INF/MANIFEST.MF')))
    }
    def mainAttributes = updatedManifest.mainAttributes
    def agentAttributes = updatedManifest.getAttributes(AGENT_ATTRIBUTES_GROUP)

    and:
    // Refresh meta data after FS operation
    targetFSInfo = fsTool.targetFSInfo

    then:
    mainAttributes.getValue('Launcher-Agent-Class') == 'dev.aspectj.maven.agent_embedder.JavaAgentLauncher'
    1 * log.debug('Setting manifest attribute \'Launcher-Agent-Class: dev.aspectj.maven.agent_embedder.JavaAgentLauncher\'')
    agentAttributes.getValue('Agent-Count') == '2'
    agentAttributes.getValue('Agent-Class-1') == 'org.aspectj.weaver.loadtime.Agent'
    !agentAttributes.getValue('Agent-Args-1')
    agentAttributes.getValue('Agent-Class-2') == 'org.acme.MyAgent'
    agentAttributes.getValue('Agent-Args-2') == 'arg1=one,arg2=two'

    and:
    !targetFSInfo.find { it.path.toString() == '/org/aspectj/weaver/loadtime/Agent.class' }
    !doCreateNestedAgentJar || targetFSInfo.find { it.path.toString() == '/BOOT-INF/lib/aspectjweaver-1.9.21.jar' }

    when:
    try (FileSystem targetJarFS = fsTool.getTargetJarFS(false)) {
      mojo.unpackAgentJar(targetJarFS, fsTool.agentJarLocation1)
      mojo.unpackAgentJar(targetJarFS, fsTool.agentJarLocation2)
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
    scenario                           | doCreateAgentJar | doCreateNestedAgentJar
    'create agent + nested agent JARs' | true             | true
    'create agent JARs only'           | true             | false
    'create nested agent JARs only'    | false            | true
//    'create no agent JARs'             | false            | false
  }

  @Unroll('#scenario')
  def 'execute embedder mojo'() {
    given:
    InMemoryFileSystemTool fsTool = new InMemoryFileSystemTool()
      .doCreateAgentJar1(doCreateAgentJar)
      .doCreateNestedAgentJar1(doCreateNestedAgentJar)
      .doCreateAgentJar2(doCreateAgentJar)
      .doCreateNestedAgentJar2(doCreateNestedAgentJar)
    //.debug(true)
    FileSystem hostFS = fsTool.createHostFS()
    List<FileInfo> hostFSInfo = fsTool.hostFSInfo
    List<FileInfo> agentFSInfo = fsTool.agentFSInfo
    List<FileInfo> targetFSInfo = fsTool.targetFSInfo
    AgentEmbedderMojo mojo = new AgentEmbedderMojo(hostFS: hostFS, removeEmbeddedAgents: true, log: log)

    expect:
    !doCreateAgentJar || hostFSInfo.find { it.path.toString() == fsTool.agentJarLocation1 }
    !doCreateAgentJar || hostFSInfo.find { it.path.toString() == fsTool.agentJarLocation2 }
    !doCreateNestedAgentJar || targetFSInfo.find { it.path.toString() == '/BOOT-INF/lib/aspectjweaver-1.9.21.jar' }
    !doCreateNestedAgentJar || targetFSInfo.find { it.path.toString() == '/BOOT-INF/lib/my-agent-3.5.jar' }
    !targetFSInfo.find { it.path.toString() == '/org/aspectj/weaver/loadtime/Agent.class' }
    !targetFSInfo.find { it.path.toString() == '/org/acme/MyAgent.class' }

    when:
    mojo.javaAgents = [
      new JavaAgentInfo('org.aspectj', 'aspectjweaver', null, 'org.aspectj.weaver.loadtime.Agent', null, null),
      new JavaAgentInfo('org.acme', 'my-agent', null, 'org.acme.MyAgent', 'arg1=one,arg2=two', null)
    ]
    DefaultArtifact javaAgentArtifact1 = Spy(new DefaultArtifact('org.aspectj', 'aspectjweaver', '1.9.21', 'compile', 'jar', null, Mock(ArtifactHandler))) {
      // Mock Maven API, i.e. we cannot use Java NIO
      getFile() >> new File(fsTool.agentJarLocation1)
    }
    DefaultArtifact javaAgentArtifact2 = Spy(new DefaultArtifact('org.acme', 'my-agent', '3.5', 'compile', 'jar', null, Mock(ArtifactHandler))) {
      // Mock Maven API, i.e. we cannot use Java NIO
      getFile() >> new File(fsTool.agentJarLocation2)
    }
    DefaultArtifact buildArtifact = Spy(new DefaultArtifact('dev.aspectj', 'my-project', '1.0', 'compile', 'jar', null, Mock(ArtifactHandler))) {
      // Mock Maven API, i.e. we cannot use Java NIO
      getFile() >> new File(fsTool.targetJarLocation)
    }
    mojo.project = Mock(MavenProject) {
      getArtifacts() >> [javaAgentArtifact1, javaAgentArtifact2]
      getArtifact() >> buildArtifact
    }
    mojo.execute()

    Manifest updatedManifest = new Manifest()
    try (FileSystem targetJarFS = fsTool.getTargetJarFS(false)) {
      updatedManifest.read(Files.newInputStream(targetJarFS.getPath('META-INF/MANIFEST.MF')))
    }
    def mainAttributes = updatedManifest.mainAttributes
    def agentAttributes = updatedManifest.getAttributes(AGENT_ATTRIBUTES_GROUP)

    and:
    // Refresh meta data after FS operation
    targetFSInfo = fsTool.targetFSInfo

    then:
    mainAttributes.getValue('Launcher-Agent-Class') == 'dev.aspectj.maven.agent_embedder.JavaAgentLauncher'
    1 * log.debug('Setting manifest attribute \'Launcher-Agent-Class: dev.aspectj.maven.agent_embedder.JavaAgentLauncher\'')
    agentAttributes.getValue('Agent-Count') == '2'
    agentAttributes.getValue('Agent-Class-1') == 'org.aspectj.weaver.loadtime.Agent'
    !agentAttributes.getValue('Agent-Args-1')
    agentAttributes.getValue('Agent-Class-2') == 'org.acme.MyAgent'
    agentAttributes.getValue('Agent-Args-2') == 'arg1=one,arg2=two'

    and:
    targetFSInfo.find { it.path.toString() == '/org/aspectj/weaver/loadtime/Agent.class' }
    !targetFSInfo.find { it.path.toString() == '/BOOT-INF/lib/aspectjweaver-1.9.21.jar' }

    and:
    (900.._) * log.debug({ String msg -> msg.startsWith('Unpacking: ') })
    (doCreateNestedAgentJar ? 1 : 0) * log.info('Removing embedded java agent: /BOOT-INF/lib/aspectjweaver-1.9.21.jar')

    cleanup:
    hostFS?.close()

    where:
    scenario                           | doCreateAgentJar | doCreateNestedAgentJar
    'create agent + nested agent JARs' | true             | true
    'create agent JARs only'           | true             | false
    'create nested agent JARs only'    | false            | true
//    'create no agent JARs'             | false            | false
  }

  @Unroll('#scenario')
  def 'path separator is adjusted correctly'() {
    given:
    FileSystem fileSystem = Mock()
    fileSystem.getSeparator() >> fsSeparator

    when:
    def adjustedPath = new AgentEmbedderMojo().adjustPathSeparatorToHostFS(inputPath, fileSystem)

    then:
    adjustedPath.contains(fsSeparator)
    !adjustedPath.contains(File.separator) || File.separator == fsSeparator

    where:
    scenario                              | fsSeparator | inputPath
    'backslash separator, backslash path' | '\\'        | 'c:\\Users\\me\\Documents\\foo.txt'
    'slash separator, backslash path'     | '/'         | 'c:\\Users\\me\\Documents\\foo.txt'
    'backslash separator, slash path'     | '\\'        | '../other/directory/hello.html'
    'slash separator, slash path'         | '/'         | '../other/directory/hello.html'
    'backslash separator, mixed path'     | '\\'        | 'c:\\Users\\me\\repository\\org/acme/foo/1.0/foo-1.0.jar'
    'slash separator, mixed path'         | '/'         | 'c:\\Users\\me\\repository\\org/acme/foo/1.0/foo-1.0.jar'
  }
}

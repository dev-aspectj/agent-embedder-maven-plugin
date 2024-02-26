import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/*
  Test setup (identical for each IT)

  Redefine variables injected by Maven Invoker, making them known and type-safe for better IDE code completion.
  See https://maven.apache.org/plugins/maven-invoker-plugin/examples/post-build-script.html.

  During IT development, when re-running the script manually from target/it/MyTestName/verify.groovy for shorter
  turn-around times, we want to use fixed variable values, because there is no Maven Invoker around to inject them.
 */

boolean invokerVariablesBound = binding.hasVariable('basedir')

File basedir = invokerVariablesBound ? basedir : new File('.').canonicalFile
File localRepositoryPath = invokerVariablesBound ? localRepositoryPath : new File('../local-repo').canonicalFile
Map<String, String> context = invokerVariablesBound ? context : [:]
String mavenVersion = invokerVariablesBound ? mavenVersion : '3.9.5'

/*
  Integration test (IT)
 */

File buildLog = new File(basedir, 'build.log')
List<String> logLines = buildLog.readLines()

// --------------------------------------------------------
// Inspect Maven build output
// --------------------------------------------------------

// Embedded java agents are removed
List<String> removeEmbeddedJarLines = logLines.grep(~/.*Removing embedded java agent: .*/)
assert removeEmbeddedJarLines.size() == 2
assert removeEmbeddedJarLines.grep(~/.*BOOT-INF\/lib\/aspectjweaver-.*\.jar/).size() == 1
assert removeEmbeddedJarLines.grep(~/.*BOOT-INF\/lib\/remove-final-agent-.*\.jar/).size() == 1

// --------------------------------------------------------
// Inspect Spring Boot runtime output
// --------------------------------------------------------

// AspectJ load-time weaving (LTW) happens
List<String> weaveInfoLines = logLines.grep(~/.*weaveinfo.*/)
assert weaveInfoLines.size() == 4
assert weaveInfoLines.grep(~/.*field-set\(java.lang.String dev.aspectj.FirstComponent.field1\).*/).size() == 1
assert weaveInfoLines.grep(~/.*field-set\(java.lang.Double dev.aspectj.FirstComponent.field4\).*/).size() == 1
assert weaveInfoLines.grep(~/.*field-set\(java.lang.Integer dev.aspectj.SecondComponent.field2\).*/).size() == 1
assert weaveInfoLines.grep(~/.*field-set\(boolean dev.aspectj.SecondComponent.field3\).*/).size() == 1

// Remove final agent is active
assert logLines.grep(~/.*Remove final agent seems to be inactive.*/).size() == 0
assert logLines.contains('[Remove Final Agent] Removing final from class dev.aspectj.FirstComponent')
assert logLines.contains('[Remove Final Agent] Removing final from class dev.aspectj.SecondComponent')

// FieldWriteAccessLogAspect kicks in
assert logLines.contains('set(String dev.aspectj.FirstComponent.field1)')
assert logLines.contains('set(Double dev.aspectj.FirstComponent.field4)')
assert logLines.contains('set(Integer dev.aspectj.SecondComponent.field2)')
assert logLines.contains('set(boolean dev.aspectj.SecondComponent.field3)')

// --------------------------------------------------------
// Inspect executable JAR with embedded agents
// --------------------------------------------------------

// Get artifact path from Maven JAR output
List<String> buildingJarLines = logLines.grep(~/.*Building jar: .*/)
assert buildingJarLines.size() == 1
def artifactPath = buildingJarLines[0].split('Building jar: ')[1]
def artifactJarEntries = new ZipFile(artifactPath).entries().toList()

// Confirm that AspectJ weaver classes have been embedded into the executable Spring Boot JAR
def aspectjWeaverClasses = artifactJarEntries
  .findAll { it.name.startsWith 'org/aspectj/weaver/' }
assert aspectjWeaverClasses.size() > 300

// Confirm that the embedded AspectJ weaver JAR has been removed from the executable Spring Boot JAR
def aspectjWeaverJars = artifactJarEntries
  .findAll { it.name ==~ /.*aspectjweaver.*\.jar/ }
assert aspectjWeaverJars.size() == 0

// Confirm that remove final agent classes have been embedded into the executable Spring Boot JAR
def removeFinalAgentClasses = artifactJarEntries
  .findAll { it.name.startsWith 'dev/aspectj/agent/RemoveFinal' }
assert removeFinalAgentClasses.size() > 2

// Confirm that the embedded remove final agent JAR has been removed from the executable Spring Boot JAR
def removeFinalAgentJars = artifactJarEntries
  .findAll { it.name ==~ /.*remove-final-agent.*\.jar/ }
assert removeFinalAgentJars.size() == 0

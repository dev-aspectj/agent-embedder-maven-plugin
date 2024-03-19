# Agent Embedder Maven Plugin

This plugin embeds java agents into an executable JAR file, executing them automatically during application start on
JRE 9+.

An executable JAR has a `Main-Class` attribute in its manifest and is executed by `java ‑jar my.jar`.

Normally, you would need additional `‑javaagent:/path/to/agent.jar` JVM arguments to start one or more java
agents, but since Java 9 there is the
[`Launcher-Agent-Class` mechanism](https://docs.oracle.com/javase/9/docs/api/java/lang/instrument/package-summary.html),
which can launch a java agent from inside the executable JAR, if the agent classes are part of the JAR. I.e., even
though this plugin works just fine on Java 8, you do need to launch the modified executable JAR on JRE 9+ to enjoy the
benefits of this JVM feature.

See the [usage](#usage) section below and the [plugin info](https://dev-aspectj.github.io/agent-embedder-maven-plugin/plugin-info.html)
page for more details about how the plugin works and how to configure it.

Unique plugin features not offered by the Java 9+ `Launcher-Agent-Class` mechanism:

* Out of the box, the JVM only supports a single agent for auto-start, not multiple ones. Multiple agents can only be
  specified on the JVM command line. But this plugin installs its own launcher agent, which in turn is capable of 
  starting **multiple java agents**.

* The JVM does not support **agent option strings** for embedded agents like the JVM command line does. This plugin,
  however, does support agent arguments via its launcher agent.

## Usage

### Embed a single java agent

Assuming that your Maven module creates an executable JAR and you want to embed java agent `org.acme:my-agent:1.3` for
automatic execution on Java 9+, simply configure this plugin as follows:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>dev.aspectj</groupId>
      <artifactId>agent-embedder-maven-plugin</artifactId>
      <version>1.0</version>
      <executions>
        <execution>
          <id>embed-agent</id>
          <goals>
            <goal>embed</goal>
          </goals>
          <configuration>
            <javaAgents>
              <agent>
                <groupId>org.acme</groupId>
                <artifactId>my-agent</artifactId>
              </agent>
            </javaAgents>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>

<dependencies>
  <dependency>
    <groupId>org.acme</groupId>
    <artifactId>my-agent</artifactId>
    <version>1.3</version>
  </dependency>
</dependencies>
```

This will replace the Maven module's main artifact - the plain executable JAR - by an enhanced executable JAR in which
the java agent has been embedded and added to the manifest to be launched automatically whenever the JAR is started by
`java ‑jar my-executable.jar`.

### Embed multiple java agents

This works just like the example above, but you simply add multiple java agent dependencies and reference them from the
plugin configuration as multiple `javaAgents/agent` descriptors. When starting the executable JAR, the agents will be
launched in the order specified in the plugin configuration.

See the [`agent-embedder:embed`](embed-mojo.html) goal description for more details.

### Advanced usage

You might have special use cases, such as:

| Use case                                                                                                                                                                                                                                                   | Configuration parameter       |
|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------|
| The java agent exists as a nested JAR inside the build artifact, as is often the case in Spring Boot executable JARs. You want to make sure that after embedding the agent classes into the main JAR, the nested JAR is removed from the  main (uber) JAR. | `removeEmbeddedAgents`        |
| The java agent's Maven coordinates include a `classifier`.                                                                                                                                                                                                 | `javaAgents/agent/classifier` |
| The java agent is configurable by means of an options string on the JVM command line via `‑javaagent:/path/to/agent.jar=option1=one,option2=two`. You wish to emulate that for the embedded agent.                                                         | `javaAgents/agent/agentArgs`  |
| The agent JAR is not a regular dependency to be found in any Maven repository but rather a library somewhere in your project folder, and you want to specify the path to the agent JAR in the plugin configuration.                                        | `javaAgents/agent/agentPath`  |
| The agent JAR is not a regular dependency to be found in any Maven repository but rather a nested JAR with a known location inside the main JAR, and you want to specify the path to the agent JAR in the plugin configuration.                            | `javaAgents/agent/agentPath`  |
| The agent JAR is missing a manifest with an `Agent-Class` entry, even though it does contain a class with a `premain` entry point usable for a java agent, and you want to specify the agent class name.                                                   | `javaAgents/agent/agentClass` |
| The agent JAR's `Agent-Class` manifest entry points to an agent class A, but you want to use an alternative agent class B.                                                                                                                                 | `javaAgents/agent/agentClass` |

See the [`agent-embedder:embed`](https://dev-aspectj.github.io/agent-embedder-maven-plugin/embed-mojo.html) goal
description for more details.

See the [Spring Boot integration test](https://github.com/dev-aspectj/agent-embedder-maven-plugin/tree/main/src/it/SpringBootAspectJ),
which embeds two java agents (AspectJ weaver and a home-brew agent making final classes non-final) into an executable
Spring Boot JAR and then runs the modified JAR to verify that indeed both agents are launched automatically and do their
respective jobs.

package dev.aspectj.maven.agent_embedder;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

public class JavaAgentLauncher {
  public static final String AGENT_ATTRIBUTES_GROUP = "dev/aspectj/agent-embedder";
  public static final String AGENT_CLASS = "Agent-Class-";
  public static final String AGENT_ARGS = "Agent-Args-";

  public static void premain(String ignoredArgs, Instrumentation inst) throws Exception {
    Manifest manifest = new Manifest();
    try (InputStream input = JavaAgentLauncher.class.getClassLoader().getResourceAsStream("META-INF/MANIFEST.MF")) {
      manifest.read(input);
    }
    Attributes attributes = manifest.getAttributes(AGENT_ATTRIBUTES_GROUP);
    int agentCount = Integer.parseInt(attributes.getValue("Agent-Count"));
    for (int i = 1; i <= agentCount; i++) {
      String agentClass = attributes.getValue(AGENT_CLASS + i);
      String agentArgs = attributes.getValue(AGENT_ARGS + i);
      System.out.printf("Starting agent %s with arguments %s%n", agentClass, agentArgs);
      Class.forName(agentClass)
        .getMethod("premain", String.class, Instrumentation.class)
        .invoke(null, agentArgs, inst);
    }
  }

  public static void agentmain(String agentArgs, Instrumentation inst) throws Exception {
    premain(agentArgs, inst);
  }
}

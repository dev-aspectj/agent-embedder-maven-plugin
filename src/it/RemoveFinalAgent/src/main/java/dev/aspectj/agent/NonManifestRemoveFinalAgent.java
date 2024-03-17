package dev.aspectj.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

import static org.objectweb.asm.ClassReader.SKIP_DEBUG;
import static org.objectweb.asm.ClassReader.SKIP_FRAMES;

/**
 * Alternate agent class which is not configured in the manifest, bug can be reached by setting the embedder plugin's
 * {@code javaAgents/agent/agentClass} configuration property
 */
public class NonManifestRemoveFinalAgent {
  public static void premain(String targetClasses, Instrumentation instrumentation) {
    RemoveFinalAgent.transform(targetClasses, instrumentation);
  }
}

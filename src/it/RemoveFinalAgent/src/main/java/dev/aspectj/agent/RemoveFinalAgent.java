package dev.aspectj.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

import static org.objectweb.asm.ClassReader.SKIP_DEBUG;
import static org.objectweb.asm.ClassReader.SKIP_FRAMES;

public class RemoveFinalAgent {
  public static void premain(String targetClasses, Instrumentation instrumentation) {
    transform(targetClasses, instrumentation);
  }

  private static void transform(String targetClasses, Instrumentation instrumentation) {
    //noinspection Convert2Lambda
    instrumentation.addTransformer(
      new ClassFileTransformer() {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
          ClassReader classReader = new ClassReader(classfileBuffer);
          // We just change class/method modifiers -> no need to visit all parts of the code
          int flags = SKIP_DEBUG | SKIP_FRAMES;
          ClassWriter classWriter = new ClassWriter(classReader, flags);
          classReader.accept(new RemoveFinalTransformer(classWriter, targetClasses), flags);
          return classWriter.toByteArray();
        }
      }
    );
  }
}

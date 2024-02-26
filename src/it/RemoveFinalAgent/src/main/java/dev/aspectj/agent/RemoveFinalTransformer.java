package dev.aspectj.agent;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.objectweb.asm.Opcodes.ASM9;

public class RemoveFinalTransformer extends ClassVisitor {
  private final List<String> targetClasses;
  private String className;

  public RemoveFinalTransformer(ClassVisitor cv, String targetClasses) {
    super(ASM9, cv);
    this.targetClasses = Arrays.asList(targetClasses.split("[, ]+"));
  }

  @Override
  public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
    className = name.replace('/', '.');
    if (!shouldTransform()) {
      cv.visit(version, access, name, signature, superName, interfaces);
      return;
    }
    if ((access & Modifier.FINAL) != 0)
      log("Removing final from class " + className);
    cv.visit(version, access & ~Modifier.FINAL, name, signature, superName, interfaces);
  }

  @Override
  public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
    if (!shouldTransform())
      return super.visitMethod(access, name, desc, signature, exceptions);
    if ((access & Modifier.FINAL) != 0)
      log("Removing final from method " + className + "." + name + desc);
    return super.visitMethod(access & ~Modifier.FINAL, name, desc, signature, exceptions);
  }

  public boolean shouldTransform() {
    return targetClasses.contains(className);
  }

  private void log(String message) {
    System.out.println("[Remove Final Agent] " + message);
  }

}

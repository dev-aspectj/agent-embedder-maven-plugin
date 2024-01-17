package dev.aspectj;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;

@Aspect
public class FieldWriteAccessLogAspect {
  @Before("set(@dev.aspectj.LogWriteAccess * *)")
  public void logFieldWriteAccess(JoinPoint joinPoint) {
    System.out.println(joinPoint);
  }
}

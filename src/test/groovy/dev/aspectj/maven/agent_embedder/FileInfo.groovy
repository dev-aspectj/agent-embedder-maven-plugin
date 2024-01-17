package dev.aspectj.maven.agent_embedder

import groovy.transform.Canonical
import groovy.transform.ToString

import java.nio.file.Path

@Canonical
@ToString(includePackage = false)
 class FileInfo {
  Path path
  long size
  boolean isDir
}

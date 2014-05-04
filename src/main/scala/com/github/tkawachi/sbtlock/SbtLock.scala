package com.github.tkawachi.sbtlock

import org.apache.maven.artifact.versioning.ComparableVersion
import sbt._
import java.nio.charset.Charset

object SbtLock {
  private[sbtlock] val DEFAULT_LOCK_FILE_NAME = "lock.sbt"
  private[this] val DEPS_HASH_PREFIX = "// LIBRARY_DEPENDENCIES_HASH "

  case class Artifact(organization: String, name: String) {
    def sbtString(revision: String) =
      Seq(organization, name, revision).map("\"" + _ + "\"").mkString(" % ")
  }

  def doLock(allModules: Seq[ModuleID], depsHash: String, lockFile: File, log: Logger): Unit = {
    val revisionsMap: Map[Artifact, Map[String, Set[String]]] =
      allModules.groupBy { m =>
        Artifact(m.organization, m.name)
      }.mapValues { modulesInArtifact =>
        modulesInArtifact.groupBy(_.revision).mapValues { modulesInRevision =>
          modulesInRevision.map(_.configurations).flatten.toSet
        }
      }

    val moduleLines = revisionsMap.map {
      case (artifact, revisions) =>
        artifact.sbtString(chooseRevision(artifact, revisions, log))
    }.toSeq.sorted.mkString(",\n  ")

    if (moduleLines.size > 0) {
      val dependencyOverrides =
        "// DON'T EDIT THIS FILE.\n" +
          s"// This file is auto generated by sbt-lock ${BuildInfo.version}.\n" +
          "// https://github.com/tkawachi/sbt-lock/\n" +
          "dependencyOverrides in ThisBuild ++= Set(\n  " +
          moduleLines + "\n" +
          ")\n" +
          DEPS_HASH_PREFIX + depsHash + "\n"

      IO.write(lockFile, dependencyOverrides)
      log.info(s"$lockFile was created. Commit it to version control system.")
    } else {
      log.info(s"No module dependency found. Skipped to write $lockFile")
    }
  }

  def readDepsHash(lockFile: File): Option[String] =
    if (lockFile.isFile) {
      val charset = Charset.forName("UTF-8")
      val lines = IO.read(lockFile, charset).split("\n")
      lines.find(_.startsWith(DEPS_HASH_PREFIX)).map(_.drop(DEPS_HASH_PREFIX.size))
    } else {
      None
    }

  def chooseRevision(artifact: Artifact, revisions: Map[String, Set[String]], log: Logger): String = {
    if (revisions.size == 1) revisions.head._1
    else {
      log.info(s"Multiple versions exist for ${artifact.organization} % ${artifact.name}:")
      val foundVersions = revisions.keys.toList.sorted.mkString(", ")
      log.info(s"  Found $foundVersions")
      val revision = latest(revisions.keys.toSet)
      log.info(s"  -> $revision is chosen.")
      revision
    }
  }

  def latest(revisions: Set[String]): String = revisions.maxBy(new ComparableVersion(_))

  def lockFile(state: State): File = {
    val extracted = Project.extract(state)
    val buildStruct = extracted.structure
    val buildUnit = buildStruct.units(buildStruct.root)

    val lockFileName = EvaluateTask.getSetting(SbtLockPlugin.sbtLockLockFile, DEFAULT_LOCK_FILE_NAME, extracted, buildStruct)
    new File(buildUnit.localBase, lockFileName)
  }

  val checkDepUpdates = (state: State) => {
    val lockFile = SbtLock.lockFile(state)
    val currentHash = ModificationCheck.hashLibraryDependencies(state)

    readDepsHash(lockFile) match {
      case Some(hashInFile) =>
        if (hashInFile != currentHash) {
          state.log.debug(s"hashInFile: $hashInFile, currentHash: $currentHash")
          state.log.info(s"libraryDependencies is updated after ${lockFile.name} was created.")
          state.log.info(s"Run `lock` to update ${lockFile.name}, or `relock` to create a new one.")
        }
      case None =>
        if (lockFile.isFile) {
          state.log.info(s"${lockFile.name} seems to be created with old version of sbt-lock.")
          state.log.info(s"Run `lock` to update ${lockFile.name},")
        }
    }
    state
  }
}

package com.github.tkawachi

import sbt._
import sbt.Keys._

private object SbtLock {

  case class Artifact(organization: String, name: String) {
    def sbtString(revision: String) =
      Seq(organization, name, revision).map("\"" + _ + "\"").mkString(" % ")
  }

  def doLock(allModules: Seq[ModuleID], outputFile: File, s: TaskStreams): Unit = {
    val revisionsMap: Map[Artifact, Set[String]] =
      allModules.groupBy { m =>
        Artifact(m.organization, m.name)
      }.mapValues(_.map(_.revision).toSet)

    val moduleLines = revisionsMap.map { case (artifact, revisions) =>
      artifact.sbtString(chooseRevision(artifact, revisions, s))
    }.toSeq.sorted.mkString(",\n  ")
    val dependencyOverrides =
      "// This file is auto generated by sbt-lock.\n" +
    "// https://github.com/tkawachi/sbt-lock/\n\n" +
    "dependencyOverrides ++= Set(\n  " +
    moduleLines +
    "\n)\n"

    IO.write(outputFile, dependencyOverrides)
    s.log.info(s"$outputFile was created.")
  }

  def chooseRevision(artifact: Artifact, revisions: Set[String], s: TaskStreams): String = {
    if (revisions.size == 1) revisions.head
    else {
      s.log.info(s"Multiple version exists for $artifact: $revisions")
      val revision = latest(revisions)
      s.log.info(s"$revision is chosen.")
      revision
    }
  }

  // FIXME
  def latest(revisions: Set[String]): String = revisions.max
}
package ch.epfl.bluebrain.nexus.delta.sdk.deletion.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport.Stage
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import doobie._
import io.circe.{Codec, Json}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import java.time.Instant
import scala.annotation.nowarn

/**
  * A report compiling the stages of the tasks run during the project deletion
  */
final case class ProjectDeletionReport(
    project: ProjectRef,
    markedDeletedAt: Instant,
    deletedAt: Instant,
    deletedBy: Subject,
    stages: Vector[Stage]
) {
  def ++(stage: Stage): ProjectDeletionReport = copy(stages = stages :+ stage)
}

object ProjectDeletionReport {

  /**
    * Create a report without registered stages
    */
  def apply(
      project: ProjectRef,
      markedDeletedAt: Instant,
      deletedAt: Instant,
      deletedBy: Subject
  ): ProjectDeletionReport =
    ProjectDeletionReport(project, markedDeletedAt, deletedAt, deletedBy, Vector.empty)

  @nowarn("cat=unused")
  implicit val projectDeletionReportCodec: Codec[ProjectDeletionReport] = {
    implicit val config: Configuration = Configuration.default
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    deriveConfiguredCodec[ProjectDeletionReport]
  }

  implicit val projectDeletionGet: Get[ProjectDeletionReport] =
    Get[Json].temap(_.as[ProjectDeletionReport].leftMap(_.message))

  /**
    * Stage of deletion
    * @param name
    *   name of the task
    * @param log
    *   log of the performed operations
    */
  final case class Stage(name: String, log: Vector[String]) {
    def ++(line: String): Stage = copy(log = log :+ line)

    //override def toString = s"""Progress of deletion task $name:\n${log.mkString("* ", "\n* ", "")}"""
  }

  object Stage {
    def empty(name: String): Stage = Stage(name, Vector.empty)

    def apply(name: String, log: String): Stage = Stage(name, Vector(log))

    @nowarn("cat=unused")
    implicit val stageEncoder: Codec[Stage] = {
      implicit val config: Configuration = Configuration.default
      deriveConfiguredCodec[Stage]
    }
  }
}

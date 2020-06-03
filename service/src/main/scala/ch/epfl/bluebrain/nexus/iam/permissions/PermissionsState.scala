package ch.epfl.bluebrain.nexus.iam.permissions

import java.time.Instant

import ch.epfl.bluebrain.nexus.iam.config.AppConfig.{HttpConfig, PermissionsConfig}
import ch.epfl.bluebrain.nexus.iam.permissions.PermissionsState.{Current, Initial}
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.iam.types.{Permission, ResourceMetadata}
import com.github.ghik.silencer.silent

/**
  * Enumeration of Permissions states.
  */
sealed trait PermissionsState extends Product with Serializable {

  /**
    * @return the current state revision
    */
  def rev: Long

  /**
    * @return the current state in a [[Resource]] representation
    */
  def resource(implicit http: HttpConfig, pc: PermissionsConfig): Resource

  /**
    * @return the current state in a [[ResourceMetadata]] representation
    */
  def resourceMetadata(implicit http: HttpConfig): ResourceMetadata

  private[permissions] def withPermissions(
      permissions: Set[Permission],
      instant: Instant,
      subject: Subject
  )(implicit pc: PermissionsConfig): Current =
    this match {
      case _: Initial =>
        Current(
          rev = 1L,
          permissions = permissions ++ pc.minimum,
          createdAt = instant,
          createdBy = subject,
          updatedAt = instant,
          updatedBy = subject
        )
      case current: Current =>
        current.copy(rev = rev + 1, permissions = permissions ++ pc.minimum, updatedAt = instant, updatedBy = subject)
    }
}

object PermissionsState {

  /**
    * Initial state for the permission set.
    */
  sealed trait Initial extends PermissionsState {
    override def rev: Long = 0L

    override def resource(implicit http: HttpConfig, pc: PermissionsConfig): Resource =
      resourceMetadata.map(_ => pc.minimum)

    override def resourceMetadata(implicit http: HttpConfig): ResourceMetadata =
      ResourceMetadata(id, rev, types, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)
  }

  /**
    * Initial state for the permission set.
    */
  final case object Initial extends Initial

  /**
    * The "current" state for the permission set, available once at least one event was emitted.
    *
    * @param rev         the current state revision
    * @param permissions the permission set
    * @param createdAt   the instant when the resource was created
    * @param createdBy   the subject that created the resource
    * @param updatedAt   the instant when the resource was last updated
    * @param updatedBy   the subject that last updated the resource
    */
  final case class Current(
      rev: Long,
      permissions: Set[Permission],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends PermissionsState {

    override def resource(implicit http: HttpConfig, @silent pc: PermissionsConfig): Resource =
      resourceMetadata.map(_ => permissions)

    override def resourceMetadata(implicit http: HttpConfig): ResourceMetadata =
      ResourceMetadata(id, rev, types, createdAt, createdBy, updatedAt, updatedBy)
  }
}

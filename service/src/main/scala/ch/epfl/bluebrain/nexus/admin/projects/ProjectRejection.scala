package ch.epfl.bluebrain.nexus.admin.projects

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes.{BadRequest, Conflict, NotFound}
import ch.epfl.bluebrain.nexus.admin.config.Contexts._
import ch.epfl.bluebrain.nexus.admin.types.ResourceRejection
import ch.epfl.bluebrain.nexus.commons.http.directives.StatusFrom
import ch.epfl.bluebrain.nexus.rdf.implicits._
import com.github.ghik.silencer.silent
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

sealed abstract class ProjectRejection(val msg: String) extends ResourceRejection

object ProjectRejection {

  /**
    * Signals an error while decoding a project JSON payload.
    *
    * @param message human readable error details
    */
  final case class InvalidProjectFormat(message: String)
      extends ProjectRejection("The project json representation is incorrectly formatted.")

  /**
    * Signals that a project cannot be created because one with the same identifier already exists.
    */
  final case class ProjectAlreadyExists(orgLabel: String, label: String)
      extends ProjectRejection(s"Project '$orgLabel/$label' already exists.")

  /**
    * Signals that an operation on a project cannot be performed due to the fact that the referenced project does not exist.
    */
  final case class ProjectNotFound(override val msg: String) extends ProjectRejection(msg)
  object ProjectNotFound {
    def apply(uuid: UUID): ProjectNotFound =
      ProjectNotFound(s"Project with uuid '${uuid.toString.toLowerCase()}' not found.")
    def apply(orgLabel: String, label: String): ProjectNotFound =
      ProjectNotFound(s"Project with label '$orgLabel/$label' not found.")
  }

  /**
    * Signals that an operation on a project cannot be performed due to the fact that the referenced parent organization does not exist.
    */
  final case class OrganizationNotFound(label: String)
      extends ProjectRejection(s"Organization with label '$label' not found.")

  /**
    * Signals that an operation on a project cannot be performed due to the fact that the referenced parent organization is deprecated.
    */
  final case class OrganizationIsDeprecated(label: String)
      extends ProjectRejection(s"Organization with label '$label' is deprecated.")

  /**
    * Signals that a project update cannot be performed due its deprecation status.
    */
  final case class ProjectIsDeprecated(uuid: UUID)
      extends ProjectRejection(s"Project with uuid '${uuid.toString.toLowerCase()}' is deprecated.")

  /**
    * Signals that a project update cannot be performed due to an incorrect revision provided.
    *
    * @param expected   latest know revision
    * @param provided the provided revision
    */
  final case class IncorrectRev(expected: Long, provided: Long)
      extends ProjectRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the project may have been updated since last seen."
      )

  @silent // the rejectionConfig is not seen as used
  implicit val projectRejectionEncoder: Encoder[ProjectRejection] = {
    implicit val rejectionConfig: Configuration = Configuration.default.withDiscriminator("@type")
    val enc                                     = deriveConfiguredEncoder[ProjectRejection].mapJson(_ addContext errorCtxUri)
    Encoder.instance(r => enc(r) deepMerge Json.obj("reason" -> Json.fromString(r.msg)))
  }

  implicit val projectStatusFrom: StatusFrom[ProjectRejection] = StatusFrom {
    case _: IncorrectRev             => Conflict
    case _: ProjectAlreadyExists     => Conflict
    case _: ProjectNotFound          => NotFound
    case _: OrganizationNotFound     => NotFound
    case _: ProjectIsDeprecated      => BadRequest
    case _: OrganizationIsDeprecated => BadRequest
    case _: InvalidProjectFormat     => BadRequest
  }

}

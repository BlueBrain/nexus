package ch.epfl.bluebrain.nexus.admin.organizations

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

sealed abstract class OrganizationRejection(val msg: String) extends ResourceRejection

object OrganizationRejection {

  /**
    * Signals an error while decoding an organization JSON payload.
    *
    * @param message human readable error details
    */
  final case class InvalidOrganizationFormat(message: String) extends OrganizationRejection(message)

  /**
    * Signals the the organization already exists.
    */
  final case class OrganizationAlreadyExists(label: String)
      extends OrganizationRejection(s"Organization with label '$label' already exists.")

  /**
    * Signals that the organization does not exist.
    */
  final case class OrganizationNotFound private (override val msg: String) extends OrganizationRejection(msg)
  object OrganizationNotFound {
    def apply(uuid: UUID): OrganizationNotFound =
      OrganizationNotFound(s"Organization with uuid '${uuid.toString.toLowerCase()}' not found.")
    def apply(label: String): OrganizationNotFound =
      new OrganizationNotFound(s"Organization with label '$label' not found.")
  }

  /**
    * Signals that the provided revision does not match the latest revision
    *
    * @param expected latest know revision
    * @param provided provided revision
    */
  final case class IncorrectRev(expected: Long, provided: Long)
      extends OrganizationRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the organization may have been updated since last seen."
      )

  @silent
  implicit val organizationRejectionEncoder: Encoder[OrganizationRejection] = {
    implicit val rejectionConfig: Configuration = Configuration.default.withDiscriminator("@type")
    val enc                                     = deriveConfiguredEncoder[OrganizationRejection].mapJson(_ addContext errorCtxUri)
    Encoder.instance(r => enc(r) deepMerge Json.obj("reason" -> Json.fromString(r.msg)))
  }

  implicit val organizationStatusFrom: StatusFrom[OrganizationRejection] = StatusFrom {
    case _: IncorrectRev              => Conflict
    case _: OrganizationAlreadyExists => Conflict
    case _: OrganizationNotFound      => NotFound
    case _: InvalidOrganizationFormat => BadRequest
  }
}

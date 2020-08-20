package ch.epfl.bluebrain.nexus.delta.exceptions

import ch.epfl.bluebrain.nexus.admin.exceptions.AdminError
import ch.epfl.bluebrain.nexus.iam.types.IamError
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.config.Contexts._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

import scala.annotation.nowarn

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
abstract class ServiceError(val msg: String) extends Exception with Product with Serializable

object ServiceError {

  /**
    * Generic wrapper for errors that should not be exposed to clients.
    *
    * @param reason the underlying error reason
    */
  @SuppressWarnings(Array("IncorrectlyNamedExceptions"))
  final case class InternalError(reason: String) extends ServiceError(reason)

  @nowarn("cat=unused")
  implicit val internalErrorEncoder: Encoder[InternalError] = {
    implicit val rejectionConfig: Configuration = Configuration.default.withDiscriminator("@type")
    val enc                                     = deriveConfiguredEncoder[InternalError].mapJson(_ addContext errorCtxUri)
    Encoder.instance(r => {
      enc(r) deepMerge Json
        .obj("@type" -> Json.fromString(r.getClass.getSimpleName), "reason" -> Json.fromString(r.msg))
    })
  }

  implicit val serviceErrorEncoder: Encoder[ServiceError] = Encoder.instance {
    case i: IamError   => IamError.iamErrorEncoder(i)
    case a: AdminError => AdminError.adminErrorEncoder(a)
    case r             =>
      Json.obj(
        "@type"  -> Json.fromString(r.getClass.getSimpleName),
        "reason" -> Json.fromString(r.msg)
      ) addContext errorCtxUri
  }
}

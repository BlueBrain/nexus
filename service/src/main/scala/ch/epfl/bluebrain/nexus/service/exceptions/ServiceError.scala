package ch.epfl.bluebrain.nexus.service.exceptions

import ch.epfl.bluebrain.nexus.admin.exceptions.AdminError
import ch.epfl.bluebrain.nexus.iam.types.IamError
import io.circe.{Encoder, Json}
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.Contexts._
import com.github.ghik.silencer.silent
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder

abstract class ServiceError(val msg: String) extends Exception with Product with Serializable

object ServiceError {

  /**
    * Generic wrapper for errors that should not be exposed to clients.
    *
    * @param reason the underlying error reason
    */
  final case class InternalError(reason: String) extends ServiceError(reason)

  @silent
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
    case r =>
      println("dupa dupa")
      Json.obj("@type" -> Json.fromString(r.getClass.getSimpleName), "reason" -> Json.fromString(r.msg)) addContext errorCtxUri
  }
}

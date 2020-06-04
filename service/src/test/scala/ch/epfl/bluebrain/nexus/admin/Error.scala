package ch.epfl.bluebrain.nexus.admin

import ch.epfl.bluebrain.nexus.admin.config.Contexts
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import shapeless.Typeable

/**
  * An error representation that can be uniquely identified by its type.
  *
  * @param `@type`    the unique code for this error
  * @param reason     a reason for why the error occurred
  * @param `@context` the JSON-LD context
  */
final case class Error(`@type`: String, reason: String, `@context`: String)

object Error {

  /**
    * Constructs an error with the default error context value.
    *
    * @param `@type` the unique code for this error
    * @param reason a reason for why the error occurred
    */
  def apply(`@type`: String, reason: String): Error =
    Error(`@type`, reason, Contexts.errorCtxUri.asUri)

  /**
    * Provides the class name for ''A''s that have a [[shapeless.Typeable]] typeclass instance.
    *
    * @tparam A a generic type parameter
    * @return class name of A
    */
  final def classNameOf[A: Typeable]: String = {
    val describe = implicitly[Typeable[A]].describe
    describe.substring(0, describe.lastIndexOf('.'))
  }

  implicit val errorDecoder: Decoder[Error] = deriveDecoder[Error]
}

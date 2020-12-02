package ch.epfl.bluebrain.nexus.delta.sdk.model.identities

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import io.circe.{Encoder, JsonObject}

/**
  * Data type that represents the collection of identities of the client. A caller must be either Anonymous or a
  * specific User.
  *
  * @param subject    the subject identity of the caller (User or Anonymous)
  * @param identities the full collection of identities, including the subject
  */
final case class Caller private (subject: Subject, identities: Set[Identity])

object Caller {

  /**
    * The constant anonymous caller.
    */
  val Anonymous: Caller = Caller(Identity.Anonymous, Set(Identity.Anonymous))

  /**
    * Allows the creation of a Caller without any validations.
    *
    * @param subject    the subject identity of the caller (User or Anonymous)
    * @param identities the full collection of identities, including the subject
    */
  def unsafe(subject: Subject, identities: Set[Identity] = Set.empty): Caller =
    if (identities.contains(subject)) new Caller(subject, identities)
    else new Caller(subject, identities + subject)

  implicit final def callerEncoder(implicit I: Encoder[Identity], base: BaseUri): Encoder.AsObject[Caller] =
    Encoder.AsObject.instance[Caller] { caller =>
      JsonObject.singleton(
        "identities",
        Encoder.encodeList(I)(caller.identities.toList.sortBy(_.id.toString))
      )
    }

  private val context                                                                                   = ContextValue(contexts.metadata, contexts.identities)
  implicit def callerJsonLdEncoder(implicit I: Encoder[Identity], base: BaseUri): JsonLdEncoder[Caller] =
    JsonLdEncoder.computeFromCirce(context)

}

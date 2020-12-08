package ch.epfl.bluebrain.nexus.delta.routes.models

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

import scala.annotation.nowarn

/**
  * A collection of tags used as output on the routes
  */
final case class Tags(tags: Seq[Tag])

object Tags {
  final def apply(values: Map[Label, Long]): Tags              =
    Tags(values.map { case (tag, rev) => Tag(rev, tag) }.toSeq)

  @nowarn("cat=unused")
  implicit private val tagFieldsEncoder: Encoder.AsObject[Tag] = deriveEncoder[Tag]
  implicit private val tagsEncoder: Encoder.AsObject[Tags]     = deriveEncoder[Tags]

  implicit final val tagsJsonLdEncoder: JsonLdEncoder[Tags] =
    JsonLdEncoder.computeFromCirce(id = BNode.random, ctx = ContextValue(contexts.tags))
}

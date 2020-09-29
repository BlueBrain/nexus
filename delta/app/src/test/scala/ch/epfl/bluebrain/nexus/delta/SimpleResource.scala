package ch.epfl.bluebrain.nexus.delta

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{JsonLd, JsonLdEncoder}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RawJsonLdContext
import monix.bio.IO
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import io.circe.{Json, JsonObject}
import io.circe.syntax._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv

final case class SimpleResource(id: Iri, rev: Long, createdAt: Instant, name: String, age: Int)

object SimpleResource extends CirceLiteral {

  val contextIri: Iri =
    iri"http://example.com/contexts/simple-resource.json"

  val context: Json =
    json"""{ "@context": {"_rev": "${nxv + "rev"}", "_createdAt": "${nxv + "createdAt"}", "@vocab": "${nxv.base}"} }"""

  implicit val jsonLdEncoder: JsonLdEncoder[SimpleResource] = new JsonLdEncoder[SimpleResource] {

    override def apply(v: SimpleResource): IO[RdfError, JsonLd] =
      IO.pure {
        JsonLd.compactedUnsafe(
          JsonObject.empty
            .add("@id", v.id.asJson)
            .add("name", v.name.asJson)
            .add("age", v.name.asJson)
            .add("_rev", v.rev.asJson)
            .add("_createdAt", v.createdAt.asJson),
          defaultContext,
          v.id
        )
      }

    override val defaultContext: RawJsonLdContext = RawJsonLdContext(contextIri.asJson)
  }
}

package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

import java.time.Instant

final case class SimpleResource(id: Iri, rev: Long, createdAt: Instant, name: String, age: Int)

object SimpleResource extends CirceLiteral {

  val contextIri: Iri =
    iri"http://example.com/contexts/simple-resource.json"

  val context: Json =
    json"""{ "@context": {"_rev": "${nxv + "rev"}", "_createdAt": "${nxv + "createdAt"}", "@vocab": "${nxv.base}"} }"""

  implicit private val simpleResourceEncoder: Encoder.AsObject[SimpleResource] =
    Encoder.AsObject.instance { v =>
      JsonObject.empty
        .add("@id", v.id.asJson)
        .add("name", v.name.asJson)
        .add("age", v.name.asJson)
        .add("_rev", v.rev.asJson)
        .add("_createdAt", v.createdAt.asJson)
    }

  implicit val simpleResourceJsonLdEncoder: JsonLdEncoder[SimpleResource] =
    JsonLdEncoder.computeFromCirce(_.id, ContextValue(contextIri))

}

package ch.epfl.bluebrain.nexus.delta.plugins.search

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.search.models.SearchRejection
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts => nxvContexts}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.IO

trait Search {

  /**
    * Queries the underlying elasticsearch search indices that the ''caller'' has access to
    *
    * @param payload the query payload
    */
  def query(payload: JsonObject, qp: Uri.Query)(implicit caller: Caller): IO[SearchRejection, Json]
}

object Search {

  //TODO: modify
  final def apply: Search = new Search {
    override def query(payload: JsonObject, qp: Uri.Query)(implicit caller: Caller): IO[SearchRejection, Json] =
      IO.pure(Json.obj("test" -> true.asJson))
  }

  object contexts {
    val fieldsConfig = nxvContexts + "fields-config.json"
  }
}

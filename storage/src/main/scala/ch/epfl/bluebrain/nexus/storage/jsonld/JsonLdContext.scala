package ch.epfl.bluebrain.nexus.storage.jsonld

import akka.http.scaladsl.model.Uri
import io.circe.Json

object JsonLdContext {

  object keywords {
    val context = "@context"
  }

  /**
    * Adds a context Iri to an existing @context, or creates an @context with the Iri as a value.
    */
  def addContext(json: Json, contextIri: Uri): Json = {
    val jUriString = Json.fromString(contextIri.toString)

    json.mapObject { obj =>
      obj(keywords.context) match {
        case None           => obj.add(keywords.context, jUriString)
        case Some(ctxValue) =>
          (ctxValue.asObject, ctxValue.asArray, ctxValue.asString) match {
            case (Some(co), _, _) if co.isEmpty                         => obj.add(keywords.context, jUriString)
            case (_, Some(ca), _) if ca.isEmpty                         => obj.add(keywords.context, jUriString)
            case (_, _, Some(cs)) if cs.isEmpty                         => obj.add(keywords.context, jUriString)
            case (Some(co), _, _) if !co.values.exists(_ == jUriString) =>
              obj.add(keywords.context, Json.arr(ctxValue, jUriString))
            case (_, Some(ca), _) if !ca.contains(jUriString)           =>
              obj.add(keywords.context, Json.fromValues(ca :+ jUriString))
            case (_, _, Some(cs)) if cs != contextIri.toString          =>
              obj.add(keywords.context, Json.arr(ctxValue, jUriString))
            case _                                                      => obj
          }
      }
    }
  }
}

package ch.epfl.bluebrain.nexus.kg.config

import ch.epfl.bluebrain.nexus.commons.test.Resources._
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import io.circe.Json
import org.apache.jena.rdf.model.Model

@SuppressWarnings(Array("OptionGet"))
object Schemas {

  val base = url"https://bluebrain.github.io/nexus/schemas/"

  //Schema URIs
  val shaclSchemaUri: AbsoluteIri         = base + "shacl-20170720.ttl"
  val resolverSchemaUri: AbsoluteIri      = base + "resolver.json"
  val unconstrainedSchemaUri: AbsoluteIri = base + "unconstrained.json"
  val fileSchemaUri: AbsoluteIri          = base + "file.json"
  val viewSchemaUri: AbsoluteIri          = base + "view.json"
  val storageSchemaUri: AbsoluteIri       = base + "storage.json"
  val archiveSchemaUri: AbsoluteIri       = base + "archive.json"
  val ontologySchemaUri: AbsoluteIri      = base + "ontology.json"

  //Schema payloads
  val resolverSchema: Json = jsonContentOf("/schemas/resolver.json")
  val viewSchema: Json     = jsonContentOf("/schemas/view.json")
  val storageSchema: Json  = jsonContentOf("/schemas/storage.json")
  val archiveSchema: Json  = jsonContentOf("/schemas/archive.json")

  //Schema models
  val resolverSchemaModel: Model =
    resolveSchema(resolverSchema).toGraph(resolverSchemaUri).toOption.get.asJena
  val viewSchemaModel: Model     =
    resolveSchema(viewSchema).toGraph(viewSchemaUri).toOption.get.asJena
  val storageSchemaModel: Model  =
    resolveSchema(storageSchema).toGraph(storageSchemaUri).toOption.get.asJena
  val archiveSchemaModel: Model  =
    resolveSchema(archiveSchema).toGraph(archiveSchemaUri).toOption.get.asJena

  // Schema references
  val viewRef          = viewSchemaUri.ref
  val storageRef       = storageSchemaUri.ref
  val archiveRef       = archiveSchemaUri.ref
  val resolverRef      = resolverSchemaUri.ref
  val unconstrainedRef = unconstrainedSchemaUri.ref
  val shaclRef         = shaclSchemaUri.ref
  val fileRef          = fileSchemaUri.ref

  private def resolveSchema(schema: Json): Json = {
    val ctx = schema.hcursor.downField("@context").as[Json].getOrElse(Json.obj())
    schema.replaceContext(Json.obj("@context" -> removeContextIris(ctx))).appendContextOf(shaclCtx)
  }

  private def removeContextIris(ctx: Json): Json =
    (ctx.asString, ctx.asObject, ctx.asArray) match {
      case (Some(_), _, _)   => Json.obj()
      case (_, Some(_), _)   => ctx
      case (_, _, Some(arr)) => Json.arr(arr.filter(!_.isString): _*)
      case _                 => ctx
    }

}
